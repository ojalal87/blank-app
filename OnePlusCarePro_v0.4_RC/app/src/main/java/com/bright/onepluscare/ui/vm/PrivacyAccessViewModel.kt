package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.data.AccessSummary
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AccessWindow(val ms: Long) { DAY(86_400_000L), WEEK(7 * 86_400_000L), ALL(Long.MAX_VALUE) }

data class PrivacyAccessLocal(
    val loading: Boolean = false,
    val items: List<AccessSummary>? = null,
    val unavailable: Boolean = false,
    val window: AccessWindow = AccessWindow.DAY,
    val backgroundOnly: Boolean = false,
    val loadedAtMillis: Long = 0,
)

data class PrivacyAccessUiState(val local: PrivacyAccessLocal = PrivacyAccessLocal(), val shizuku: ShizukuStatus = ShizukuStatus()) {
    val ready: Boolean get() = shizuku.running && shizuku.permissionGranted
    val visible: List<AccessSummary>
        get() = local.items.orEmpty().filter { it.mostRecentAgoMs <= local.window.ms && (!local.backgroundOnly || it.anyBackground) }
}

class PrivacyAccessViewModel(private val g: AppGraph) : ViewModel() {
    private val local = MutableStateFlow(PrivacyAccessLocal())

    val state: StateFlow<PrivacyAccessUiState> = combine(local, g.shizuku.status) { l, s -> PrivacyAccessUiState(l, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacyAccessUiState())

    fun load() {
        if (local.value.loading) return
        viewModelScope.launch {
            local.update { it.copy(loading = true) }
            val r = g.privacyAccess.load()
            local.update { it.copy(loading = false, items = r ?: it.items, unavailable = r == null, loadedAtMillis = System.currentTimeMillis()) }
        }
    }

    fun setWindow(w: AccessWindow) = local.update { it.copy(window = w) }
    fun setBackgroundOnly(on: Boolean) = local.update { it.copy(backgroundOnly = on) }
    fun requestShizukuPermission() = g.shizuku.requestPermission()
    fun openApp(pkg: String) = g.intents.openAppDetails(pkg)
    fun openPrivacyDashboard() = g.intents.open(SystemIntents.Target.PRIVACY)
}
