package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.R
import com.bright.onepluscare.core.apps.AppRecord
import com.bright.onepluscare.core.tweaks.ChangeStatus
import com.bright.onepluscare.core.tweaks.DebloatRisk
import com.bright.onepluscare.core.tweaks.PackageState
import com.bright.onepluscare.core.tweaks.SystemPackage
import com.bright.onepluscare.core.tweaks.TweakChange
import com.bright.onepluscare.core.tweaks.TweakKind
import com.bright.onepluscare.data.TunerSnapshot
import com.bright.onepluscare.data.TweakResult
import com.bright.onepluscare.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class DebloatFilter { RECOMMENDED, CAUTION, ALL, CHANGED }

sealed interface TunerConfirm {
    data class Debloat(val pkg: String, val label: String, val risk: DebloatRisk, val uninstall: Boolean) : TunerConfirm
    data class Density(val dpi: Int) : TunerConfirm
    data object RevertAll : TunerConfirm
}

data class TunerLocal(
    val loading: Boolean = false,
    val snapshot: TunerSnapshot? = null,
    val packages: List<SystemPackage>? = null,
    val packagesLoading: Boolean = false,
    val apps: List<AppRecord>? = null,
    val expert: Boolean = false,
    val filter: DebloatFilter = DebloatFilter.RECOMMENDED,
    val query: String = "",
    val busy: Boolean = false,
    val confirm: TunerConfirm? = null,
    val message: String? = null,
)

data class TunerUiState(
    val shizuku: ShizukuStatus = ShizukuStatus(),
    val local: TunerLocal = TunerLocal(),
    val changes: List<TweakChange> = emptyList(),
    val refreshRates: List<Float> = emptyList(),
) {
    val ready: Boolean get() = shizuku.running && shizuku.permissionGranted
    val active: List<TweakChange> get() = changes.filter { it.status == ChangeStatus.APPLIED }.sortedByDescending { it.createdAtMillis }
    val backgroundFixed: Set<String> get() = active.filter { it.kind == TweakKind.BACKGROUND_FIX }.mapTo(HashSet()) { it.target }

    val visiblePackages: List<SystemPackage>
        get() {
            val q = local.query.trim().lowercase(Locale.ROOT)
            return local.packages.orEmpty().filter { p ->
                val matches = q.isEmpty() || p.info.pkg.lowercase(Locale.ROOT).contains(q) || (p.info.label?.lowercase(Locale.ROOT)?.contains(q) == true)
                val inFilter = when (local.filter) {
                    DebloatFilter.RECOMMENDED -> p.info.risk == DebloatRisk.RECOMMENDED
                    DebloatFilter.CAUTION -> p.info.risk == DebloatRisk.CAUTION
                    DebloatFilter.ALL -> local.expert && p.info.risk != DebloatRisk.PROTECTED
                    DebloatFilter.CHANGED -> p.state != PackageState.ENABLED
                }
                matches && inFilter
            }
        }
}

class TunerViewModel(private val g: AppGraph) : ViewModel() {
    private val local = MutableStateFlow(TunerLocal())

    private val rates: List<Float> by lazy { runCatching { g.tweaks.supportedRefreshRates() }.getOrDefault(emptyList()) }

    val state: StateFlow<TunerUiState> = combine(g.shizuku.status, local, g.tweaks.changes) { s, l, c ->
        TunerUiState(s, l, c, rates)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TunerUiState())

    fun refresh() {
        g.shizuku.refresh()
        if (!g.shizuku.status.value.permissionGranted) return
        viewModelScope.launch {
            local.update { it.copy(loading = true) }
            val snap = g.tweaks.snapshot()
            local.update { it.copy(loading = false, snapshot = snap) }
        }
    }

    fun requestShizukuPermission() = g.shizuku.requestPermission()

    fun loadPackages() {
        if (local.value.packagesLoading) return
        viewModelScope.launch {
            local.update { it.copy(packagesLoading = true) }
            val list = g.tweaks.systemPackages()
            local.update { it.copy(packagesLoading = false, packages = list ?: it.packages, message = if (list == null) str(R.string.tuner_msg_unavailable) else it.message) }
        }
    }

    fun loadApps() {
        if (local.value.apps != null) return
        viewModelScope.launch {
            val inv = g.results.value.apps ?: g.apps.load().also { inv -> g.updateResults { it.copy(apps = inv) } }
            local.update { it.copy(apps = inv.apps.filter { a -> !a.isSystem || a.packageName.startsWith("com.google.android.apps.messaging") }.sortedBy { a -> a.label.lowercase(Locale.ROOT) }) }
        }
    }

    fun setExpert(on: Boolean) = local.update { it.copy(expert = on, filter = if (!on && it.filter == DebloatFilter.ALL) DebloatFilter.RECOMMENDED else it.filter) }
    fun setFilter(f: DebloatFilter) = local.update { it.copy(filter = f) }
    fun setQuery(q: String) = local.update { it.copy(query = q.take(64)) }

    fun setAnimation(scale: String) = launchTweak { g.tweaks.setAnimation(scale) }
    fun setRefreshRate(hz: Float) = launchTweak { g.tweaks.setRefreshRate(hz) }
    fun setPrivateDns(host: String?) = launchTweak { g.tweaks.setPrivateDns(host) }
    fun setPhantom(disabled: Boolean) = launchTweak { g.tweaks.setPhantomRestrictionsDisabled(disabled) }

    fun toggleBackground(pkg: String) {
        val existing = state.value.active.firstOrNull { it.kind == TweakKind.BACKGROUND_FIX && it.target == pkg }
        if (existing != null) revert(existing) else launchTweak(reload = false) { g.tweaks.fixBackground(pkg) }
    }

    fun requestDensity(dpi: Int) = local.update { it.copy(confirm = TunerConfirm.Density(dpi)) }

    fun requestDebloat(p: SystemPackage, uninstall: Boolean) =
        local.update { it.copy(confirm = TunerConfirm.Debloat(p.info.pkg, p.info.label ?: p.info.pkg, p.info.risk, uninstall)) }

    fun requestRevertAll() = local.update { it.copy(confirm = TunerConfirm.RevertAll) }

    fun dismissConfirm() = local.update { it.copy(confirm = null) }

    fun confirm() {
        val c = local.value.confirm ?: return
        local.update { it.copy(confirm = null) }
        when (c) {
            is TunerConfirm.Density -> launchTweak { g.tweaks.setDensity(c.dpi) }
            is TunerConfirm.Debloat -> launchTweak(reloadPackages = true) { g.tweaks.debloat(c.pkg, c.uninstall) }
            TunerConfirm.RevertAll -> viewModelScope.launch {
                local.update { it.copy(busy = true) }
                val failed = g.tweaks.revertAll()
                local.update { it.copy(busy = false, message = if (failed == 0) str(R.string.tuner_msg_reverted_all) else str(R.string.tuner_msg_revert_failed_n, failed)) }
                refresh()
                if (local.value.packages != null) loadPackages()
            }
        }
    }

    fun restore(p: SystemPackage) {
        val active = state.value.active.firstOrNull { it.target == p.info.pkg && (it.kind == TweakKind.DEBLOAT_DISABLE || it.kind == TweakKind.DEBLOAT_UNINSTALL) }
        if (active != null) revert(active, reloadPackages = true) else launchTweak(reloadPackages = true) { g.tweaks.restorePackage(p.info.pkg, p.state) }
    }

    fun revert(change: TweakChange, reloadPackages: Boolean = false) {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val ok = g.tweaks.revert(change)
            local.update { it.copy(busy = false, message = str(if (ok) R.string.tuner_msg_reverted else R.string.tuner_msg_revert_failed)) }
            refresh()
            if (reloadPackages || change.kind == TweakKind.DEBLOAT_DISABLE || change.kind == TweakKind.DEBLOAT_UNINSTALL) loadPackages()
        }
    }

    fun clearFinished() = g.tweaks.clearFinished()

    fun messageShown() = local.update { it.copy(message = null) }

    private fun launchTweak(reload: Boolean = true, reloadPackages: Boolean = false, block: suspend () -> TweakResult) {
        if (local.value.busy) return
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val r = block()
            val msg = when (r) {
                TweakResult.Applied -> str(R.string.tuner_msg_applied)
                TweakResult.Unavailable -> str(R.string.tuner_msg_unavailable)
                TweakResult.Invalid -> str(R.string.tuner_msg_invalid)
                is TweakResult.Failed -> str(if (r.rolledBack) R.string.tuner_msg_failed_rolled_back else R.string.tuner_msg_failed)
            }
            local.update { it.copy(busy = false, message = msg) }
            if (reload) refresh()
            if (reloadPackages) loadPackages()
        }
    }

    private fun str(id: Int, vararg args: Any) = g.appContext.getString(id, *args)
}
