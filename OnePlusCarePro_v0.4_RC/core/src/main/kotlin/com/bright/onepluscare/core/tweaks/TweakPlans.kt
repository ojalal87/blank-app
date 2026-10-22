package com.bright.onepluscare.core.tweaks

import com.bright.onepluscare.core.io.AtomicTextFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID

@Serializable
enum class TweakKind {
    @SerialName("background_fix") BACKGROUND_FIX,
    @SerialName("debloat_disable") DEBLOAT_DISABLE,
    @SerialName("debloat_uninstall") DEBLOAT_UNINSTALL,
    @SerialName("package_restore") PACKAGE_RESTORE,
    @SerialName("animation_scale") ANIMATION_SCALE,
    @SerialName("display_density") DISPLAY_DENSITY,
    @SerialName("refresh_rate") REFRESH_RATE,
    @SerialName("private_dns") PRIVATE_DNS,
    @SerialName("phantom_process") PHANTOM_PROCESS,
}

@Serializable
enum class ChangeStatus { @SerialName("pending") PENDING, @SerialName("applied") APPLIED, @SerialName("failed") FAILED, @SerialName("reverted") REVERTED, @SerialName("superseded") SUPERSEDED }

/** A planned change: what to run, and exactly how to put things back. */
@Serializable
data class TweakChange(
    val id: String,
    val kind: TweakKind,
    /** Package name, or the chosen value (e.g. "0.5", "420", "dns.adguard-dns.com"). */
    val target: String,
    val createdAtMillis: Long,
    val status: ChangeStatus,
    val apply: List<TweakOp>,
    val undo: List<TweakOp>,
) {
    /** Changes of the same kind/subject replace each other; the first one's undo restores the original state. */
    val slot: String get() = when (kind) {
        TweakKind.BACKGROUND_FIX, TweakKind.DEBLOAT_DISABLE, TweakKind.DEBLOAT_UNINSTALL, TweakKind.PACKAGE_RESTORE -> "pkg:$target"
        else -> kind.name
    }
}

/** Pure planners. Callers pass the *current* state they read through read-only ops. */
object TweakPlanner {
    private fun change(kind: TweakKind, target: String, apply: List<TweakOp>, undo: List<TweakOp>, now: Long) =
        TweakChange(UUID.randomUUID().toString(), kind, target, now, ChangeStatus.PENDING, apply, undo)

    private fun restoreSetting(spec: SettingSpec, previous: String?): TweakOp =
        if (previous != null && spec.rule.accepts(previous)) TweakOp.SettingPut(spec.ns, spec.key, previous) else TweakOp.SettingDelete(spec.ns, spec.key)

    private val ANIMATION_KEYS = listOf(SettingsAllowList.WINDOW_ANIMATION, SettingsAllowList.TRANSITION_ANIMATION, SettingsAllowList.ANIMATOR_DURATION)

    fun animation(scale: String, previous: Map<String, String?>, now: Long): TweakChange? {
        if (scale !in SettingsAllowList.ANIMATION_SCALES) return null
        return change(
            TweakKind.ANIMATION_SCALE, scale,
            ANIMATION_KEYS.map { TweakOp.SettingPut(it.ns, it.key, scale) },
            ANIMATION_KEYS.map { restoreSetting(it, previous[it.key]) },
            now,
        )
    }

    fun animationReads(): List<TweakOp.SettingGet> = ANIMATION_KEYS.map { TweakOp.SettingGet(it.ns, it.key) }

    fun formatRate(hz: Float): String = String.format(Locale.ROOT, "%.1f", hz)

    /** Pins both peak and minimum refresh rate (OxygenOS otherwise drops to 60 Hz in many apps). */
    fun refreshRate(hz: Float, prevPeak: String?, prevMin: String?, now: Long): TweakChange? {
        val v = formatRate(hz)
        if (!SettingsAllowList.PEAK_REFRESH.rule.accepts(v)) return null
        return change(
            TweakKind.REFRESH_RATE, v,
            listOf(TweakOp.SettingPut(SettingNamespace.SYSTEM, "peak_refresh_rate", v), TweakOp.SettingPut(SettingNamespace.SYSTEM, "min_refresh_rate", v)),
            listOf(restoreSetting(SettingsAllowList.PEAK_REFRESH, prevPeak), restoreSetting(SettingsAllowList.MIN_REFRESH, prevMin)),
            now,
        )
    }

    fun privateDns(host: String?, prevMode: String?, prevHost: String?, now: Long): TweakChange? {
        val apply = if (host == null) {
            listOf(TweakOp.SettingPut(SettingNamespace.GLOBAL, "private_dns_mode", "opportunistic"))
        } else {
            if (!ValueRule.Hostname.accepts(host)) return null
            listOf(
                TweakOp.SettingPut(SettingNamespace.GLOBAL, "private_dns_specifier", host),
                TweakOp.SettingPut(SettingNamespace.GLOBAL, "private_dns_mode", "hostname"),
            )
        }
        return change(
            TweakKind.PRIVATE_DNS, host ?: "automatic", apply,
            listOf(restoreSetting(SettingsAllowList.PRIVATE_DNS_HOST, prevHost), restoreSetting(SettingsAllowList.PRIVATE_DNS_MODE, prevMode)),
            now,
        )
    }

    /** Android 14+: stop the system killing child processes of apps (Termux, some VPN and backup apps). */
    fun phantomProcesses(disableRestrictions: Boolean, previous: String?, now: Long): TweakChange {
        val value = if (disableRestrictions) "false" else "true"
        return change(
            TweakKind.PHANTOM_PROCESS, value,
            listOf(TweakOp.SettingPut(SettingNamespace.GLOBAL, SettingsAllowList.PHANTOM_PROCS.key, value)),
            listOf(restoreSetting(SettingsAllowList.PHANTOM_PROCS, previous)),
            now,
        )
    }

    /** Density is bounded to ±25% of the panel's physical density, so the UI can never become unusable. */
    fun density(dpi: Int, current: TweakParsers.Density, now: Long): TweakChange? {
        val physical = current.physical ?: return null
        if (dpi !in densityRange(physical)) return null
        val undo: TweakOp = current.override?.let { TweakOp.DensitySet(it) } ?: TweakOp.DensityReset
        val apply: TweakOp = if (dpi == physical) TweakOp.DensityReset else TweakOp.DensitySet(dpi)
        return change(TweakKind.DISPLAY_DENSITY, dpi.toString(), listOf(apply), listOf(undo), now)
    }

    fun densityRange(physical: Int): IntRange = (physical * 3 / 4).coerceAtLeast(TweakCommands.MIN_DPI)..(physical * 5 / 4).coerceAtMost(TweakCommands.MAX_DPI)

    data class BackgroundState(val idleWhitelisted: Boolean, val runAnyMode: String, val runInBackgroundMode: String, val bucketCode: Int?)

    fun backgroundReads(pkg: String): List<TweakOp> = listOf(
        TweakOp.IdleWhitelistList,
        TweakOp.AppOpGet(pkg, "RUN_ANY_IN_BACKGROUND"),
        TweakOp.AppOpGet(pkg, "RUN_IN_BACKGROUND"),
        TweakOp.StandbyGet(pkg),
    )

    /**
     * Stops Doze/App Standby/background restrictions from delaying or killing [pkg]: exempt from battery
     * optimization, allow background execution app-ops, and move to the ACTIVE standby bucket.
     * OxygenOS also has a proprietary freezer; the UI tells the user to set "Allow background activity" too.
     */
    fun backgroundFix(pkg: String, state: BackgroundState, now: Long): TweakChange? {
        if (!TweakCommands.isValidPackage(pkg)) return null
        val apply = buildList {
            if (!state.idleWhitelisted) add(TweakOp.IdleWhitelistAdd(pkg))
            add(TweakOp.AppOpSet(pkg, "RUN_ANY_IN_BACKGROUND", "allow"))
            add(TweakOp.AppOpSet(pkg, "RUN_IN_BACKGROUND", "allow"))
            add(TweakOp.StandbySet(pkg, "active"))
        }
        val undo = buildList {
            if (!state.idleWhitelisted) add(TweakOp.IdleWhitelistRemove(pkg))
            add(TweakOp.AppOpSet(pkg, "RUN_ANY_IN_BACKGROUND", state.runAnyMode))
            add(TweakOp.AppOpSet(pkg, "RUN_IN_BACKGROUND", state.runInBackgroundMode))
            state.bucketCode?.let(StandbyBuckets::nameOf)?.let { add(TweakOp.StandbySet(pkg, it)) }
        }
        return change(TweakKind.BACKGROUND_FIX, pkg, apply, undo, now)
    }

    fun debloat(pkg: String, uninstall: Boolean, now: Long): TweakChange? {
        if (!TweakCommands.isValidPackage(pkg) || DebloatClassifier.classify(pkg).risk == DebloatRisk.PROTECTED) return null
        return if (uninstall) {
            change(TweakKind.DEBLOAT_UNINSTALL, pkg, listOf(TweakOp.PackageUninstallUser(pkg)), listOf(TweakOp.PackageInstallExisting(pkg)), now)
        } else {
            change(TweakKind.DEBLOAT_DISABLE, pkg, listOf(TweakOp.PackageDisable(pkg)), listOf(TweakOp.PackageEnable(pkg)), now)
        }
    }

    /** Re-enable a package that was disabled/uninstalled outside this app (no undo: it restores factory state). */
    fun restorePackage(pkg: String, state: PackageState, now: Long): TweakChange? {
        if (!TweakCommands.isValidPackage(pkg)) return null
        val op = when (state) {
            PackageState.DISABLED -> TweakOp.PackageEnable(pkg)
            PackageState.UNINSTALLED_FOR_USER -> TweakOp.PackageInstallExisting(pkg)
            PackageState.ENABLED -> return null
        }
        return change(TweakKind.PACKAGE_RESTORE, pkg, listOf(op), emptyList(), now)
    }
}

/** Persistent record of every system change, so each one (or all) can be undone later, even after reinstalling Shizuku. */
class TweakJournal(private val file: AtomicTextFile) {
    @Serializable
    private data class Doc(val schema: Int = 1, val changes: List<TweakChange> = emptyList())

    private val lock = Any()
    private var cache: List<TweakChange>? = null

    fun all(): List<TweakChange> = synchronized(lock) { load() }

    /** Changes currently in effect, newest first. */
    fun active(): List<TweakChange> = all().filter { it.status == ChangeStatus.APPLIED }.sortedByDescending { it.createdAtMillis }

    fun activeFor(slot: String): TweakChange? = active().firstOrNull { it.slot == slot }

    /**
     * Record a planned change before running it. If a change already occupies the same slot, the new one
     * inherits that change's undo (the true original state) and the old one is marked superseded.
     */
    fun begin(change: TweakChange): TweakChange = synchronized(lock) {
        val previous = load().filter { it.status == ChangeStatus.APPLIED && it.slot == change.slot }.maxByOrNull { it.createdAtMillis }
        val merged = if (previous != null && change.kind == previous.kind) change.copy(undo = previous.undo) else change
        save(load() + merged)
        merged
    }

    fun mark(id: String, status: ChangeStatus) = synchronized(lock) {
        val current = load()
        val target = current.firstOrNull { it.id == id } ?: return@synchronized
        val updated = current.map { c ->
            when {
                c.id == id -> c.copy(status = status)
                // Once the new change is in effect, older applied changes in the same slot are superseded.
                status == ChangeStatus.APPLIED && c.status == ChangeStatus.APPLIED && c.slot == target.slot && c.kind == target.kind -> c.copy(status = ChangeStatus.SUPERSEDED)
                // A package restore cancels any earlier debloat of that package.
                status == ChangeStatus.APPLIED && target.kind == TweakKind.PACKAGE_RESTORE && c.status == ChangeStatus.APPLIED && c.slot == target.slot -> c.copy(status = ChangeStatus.REVERTED)
                else -> c
            }
        }
        save(updated)
    }

    fun clearInactive() = synchronized(lock) { save(load().filter { it.status == ChangeStatus.APPLIED || it.status == ChangeStatus.PENDING }) }

    private fun load(): List<TweakChange> {
        cache?.let { return it }
        val raw = file.read()
        val list = if (raw.isNullOrBlank()) emptyList() else runCatching { JSON.decodeFromString(Doc.serializer(), raw).changes }.getOrDefault(emptyList())
        cache = list
        return list
    }

    private fun save(list: List<TweakChange>) {
        file.write(JSON.encodeToString(Doc.serializer(), Doc(changes = list)))
        cache = list
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    }
}

/** Runs a change through a privileged runner with automatic rollback on the first failure. */
class TweakExecutor(private val journal: TweakJournal, private val run: suspend (TweakOp) -> OpResult) {
    sealed interface Outcome {
        data class Applied(val change: TweakChange) : Outcome
        data class Failed(val change: TweakChange, val failedOp: TweakOp, val result: OpResult, val rolledBack: Boolean) : Outcome
    }

    suspend fun apply(planned: TweakChange): Outcome {
        val change = journal.begin(planned)
        for (op in change.apply) {
            val r = run(op)
            if (!r.ok || OutputChecks.looksFailed(op, r.output)) {
                // Restore everything this change may have touched; undo ops are idempotent "set back" ops.
                val rolledBack = change.undo.all { u -> run(u).ok }
                journal.mark(change.id, ChangeStatus.FAILED)
                return Outcome.Failed(change, op, r, rolledBack)
            }
        }
        journal.mark(change.id, ChangeStatus.APPLIED)
        return Outcome.Applied(change)
    }

    /** Undo one applied change. Returns false if any undo step failed (the change stays APPLIED). */
    suspend fun revert(change: TweakChange): Boolean {
        if (change.status != ChangeStatus.APPLIED) return false
        var ok = true
        for (op in change.undo) {
            val r = run(op)
            if (!r.ok || OutputChecks.looksFailed(op, r.output)) ok = false
        }
        if (ok) journal.mark(change.id, ChangeStatus.REVERTED)
        return ok
    }

    /** Undo every active change, newest first. Returns the number that failed. */
    suspend fun revertAll(): Int = journal.active().count { !revert(it) }
}
