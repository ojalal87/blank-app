package com.bright.onepluscare.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.io.AtomicTextFile
import com.bright.onepluscare.core.tweaks.OpResult
import com.bright.onepluscare.core.tweaks.PackageInventory
import com.bright.onepluscare.core.tweaks.PackageListFilter
import com.bright.onepluscare.core.tweaks.PackageState
import com.bright.onepluscare.core.tweaks.SettingSpec
import com.bright.onepluscare.core.tweaks.SettingsAllowList
import com.bright.onepluscare.core.tweaks.SystemPackage
import com.bright.onepluscare.core.tweaks.TweakChange
import com.bright.onepluscare.core.tweaks.TweakExecutor
import com.bright.onepluscare.core.tweaks.TweakJournal
import com.bright.onepluscare.core.tweaks.TweakOp
import com.bright.onepluscare.core.tweaks.TweakParsers
import com.bright.onepluscare.core.tweaks.TweakPlanner
import com.bright.onepluscare.shizuku.ShizukuGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Current values of the tunable settings, read through Shizuku. null = unset / system default. */
data class TunerSnapshot(
    val animationScale: String?,
    val peakRefresh: String?,
    val minRefresh: String?,
    val density: TweakParsers.Density,
    val privateDnsMode: String?,
    val privateDnsHost: String?,
    val phantomMonitor: String?,
)

sealed interface TweakResult {
    data object Applied : TweakResult
    data object Unavailable : TweakResult
    data object Invalid : TweakResult
    data class Failed(val rolledBack: Boolean, val detail: String) : TweakResult
}

/**
 * System Tuner. All changes go through [TweakExecutor] (journal first, rollback on failure), one at a time,
 * and are recorded in History. Reads never change anything.
 */
class TweaksRepository(
    private val context: Context,
    private val shizuku: ShizukuGateway,
    private val history: HistoryRepository,
) {
    private val journal = TweakJournal(AtomicTextFile(File(context.filesDir, "tweak_journal.json").toPath()))
    private val executor = TweakExecutor(journal) { op -> shizuku.runOp(op) }
    private val mutex = Mutex()

    private val _changes = MutableStateFlow(journal.all())
    val changes: StateFlow<List<TweakChange>> = _changes.asStateFlow()

    fun activeChanges(): List<TweakChange> = journal.active()

    suspend fun snapshot(): TunerSnapshot? {
        if (!shizuku.ensureConnected()) return null
        suspend fun get(spec: SettingSpec) = read(TweakOp.SettingGet(spec.ns, spec.key))?.let(TweakParsers::settingValue)
        return TunerSnapshot(
            animationScale = get(SettingsAllowList.WINDOW_ANIMATION),
            peakRefresh = get(SettingsAllowList.PEAK_REFRESH),
            minRefresh = get(SettingsAllowList.MIN_REFRESH),
            density = TweakParsers.density(read(TweakOp.DensityGet).orEmpty()),
            privateDnsMode = get(SettingsAllowList.PRIVATE_DNS_MODE),
            privateDnsHost = get(SettingsAllowList.PRIVATE_DNS_HOST),
            phantomMonitor = get(SettingsAllowList.PHANTOM_PROCS),
        )
    }

    /** Refresh rates the panel actually supports (public Display API, no privilege needed). */
    fun supportedRefreshRates(): List<Float> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        return display.supportedModes.map { Math.round(it.refreshRate).toFloat() }.distinct().sorted()
    }

    suspend fun systemPackages(): List<SystemPackage>? {
        if (!shizuku.ensureConnected()) return null
        val system = read(TweakOp.PackagesList(PackageListFilter.SYSTEM)) ?: return null
        val disabled = read(TweakOp.PackagesList(PackageListFilter.DISABLED)).orEmpty()
        val all = read(TweakOp.PackagesList(PackageListFilter.SYSTEM_INCLUDING_UNINSTALLED)).orEmpty()
        return PackageInventory.build(system, disabled, all, dynamicProtected())
    }

    suspend fun setAnimation(scale: String) = applyPlanned(HistoryType.TWEAK) {
        val keys = TweakPlanner.animationReads()
        val previous = keys.associate { it.key to read(it)?.let(TweakParsers::settingValue) }
        TweakPlanner.animation(scale, previous, now())
    }

    suspend fun setRefreshRate(hz: Float) = applyPlanned(HistoryType.TWEAK) {
        val peak = read(TweakOp.SettingGet(SettingsAllowList.PEAK_REFRESH.ns, SettingsAllowList.PEAK_REFRESH.key))?.let(TweakParsers::settingValue)
        val min = read(TweakOp.SettingGet(SettingsAllowList.MIN_REFRESH.ns, SettingsAllowList.MIN_REFRESH.key))?.let(TweakParsers::settingValue)
        TweakPlanner.refreshRate(hz, peak, min, now())
    }

    suspend fun setDensity(dpi: Int) = applyPlanned(HistoryType.TWEAK) {
        TweakPlanner.density(dpi, TweakParsers.density(read(TweakOp.DensityGet) ?: return@applyPlanned null), now())
    }

    /** host null = Automatic. */
    suspend fun setPrivateDns(host: String?) = applyPlanned(HistoryType.TWEAK) {
        val mode = read(TweakOp.SettingGet(SettingsAllowList.PRIVATE_DNS_MODE.ns, SettingsAllowList.PRIVATE_DNS_MODE.key))?.let(TweakParsers::settingValue)
        val prevHost = read(TweakOp.SettingGet(SettingsAllowList.PRIVATE_DNS_HOST.ns, SettingsAllowList.PRIVATE_DNS_HOST.key))?.let(TweakParsers::settingValue)
        TweakPlanner.privateDns(host, mode, prevHost, now())
    }

    suspend fun setPhantomRestrictionsDisabled(disabled: Boolean) = applyPlanned(HistoryType.TWEAK) {
        if (Build.VERSION.SDK_INT < 34) return@applyPlanned null
        val prev = read(TweakOp.SettingGet(SettingsAllowList.PHANTOM_PROCS.ns, SettingsAllowList.PHANTOM_PROCS.key))?.let(TweakParsers::settingValue)
        TweakPlanner.phantomProcesses(disabled, prev, now())
    }

    suspend fun fixBackground(pkg: String) = applyPlanned(HistoryType.TWEAK) {
        val whitelist = TweakParsers.idleWhitelist(read(TweakOp.IdleWhitelistList) ?: return@applyPlanned null)
        val runAny = TweakParsers.appOpMode(read(TweakOp.AppOpGet(pkg, "RUN_ANY_IN_BACKGROUND")).orEmpty(), "RUN_ANY_IN_BACKGROUND")
        val runIn = TweakParsers.appOpMode(read(TweakOp.AppOpGet(pkg, "RUN_IN_BACKGROUND")).orEmpty(), "RUN_IN_BACKGROUND")
        val bucket = TweakParsers.standbyBucket(read(TweakOp.StandbyGet(pkg)).orEmpty())
        TweakPlanner.backgroundFix(pkg, TweakPlanner.BackgroundState(whitelist.contains(pkg), runAny, runIn, bucket), now())
    }

    suspend fun debloat(pkg: String, uninstall: Boolean) = applyPlanned(HistoryType.DEBLOAT) {
        if (pkg in dynamicProtected()) null else TweakPlanner.debloat(pkg, uninstall, now())
    }

    suspend fun restorePackage(pkg: String, state: PackageState) = applyPlanned(HistoryType.DEBLOAT) {
        TweakPlanner.restorePackage(pkg, state, now())
    }

    suspend fun revert(change: TweakChange): Boolean = mutex.withLock {
        if (!shizuku.ensureConnected()) return false
        val ok = executor.revert(change)
        if (ok) history.add(HistoryType.TWEAK_UNDO, count = 1, detail = change.kind.name.lowercase())
        publish()
        ok
    }

    suspend fun revertAll(): Int = mutex.withLock {
        if (!shizuku.ensureConnected()) return journal.active().size
        val active = journal.active().size
        val failed = executor.revertAll()
        history.add(HistoryType.TWEAK_UNDO, count = active - failed, detail = "all")
        publish()
        failed
    }

    fun clearFinished() {
        journal.clearInactive()
        publish()
    }

    private suspend fun applyPlanned(type: HistoryType, plan: suspend () -> TweakChange?): TweakResult = mutex.withLock {
        if (!shizuku.ensureConnected()) return TweakResult.Unavailable
        val change = plan() ?: return TweakResult.Invalid
        val result = when (val out = executor.apply(change)) {
            is TweakExecutor.Outcome.Applied -> {
                history.add(type, count = 1, detail = change.kind.name.lowercase())
                TweakResult.Applied
            }
            is TweakExecutor.Outcome.Failed -> TweakResult.Failed(out.rolledBack, out.result.output.take(200))
        }
        publish()
        result
    }

    private suspend fun read(op: TweakOp): String? {
        val r: OpResult = shizuku.runOp(op)
        return if (r.ok) r.output else null
    }

    private fun publish() {
        _changes.value = journal.all()
    }

    /** The current home app and keyboard can never be disabled (doing so can leave the phone unusable). */
    private fun dynamicProtected(): Set<String> = buildSet {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.resolveActivity(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        }
        resolved?.activityInfo?.packageName?.let(::add)
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let(::add)
        add(context.packageName)
    }

    private fun now() = System.currentTimeMillis()

}
