package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.core.security.NetKind
import com.bright.onepluscare.data.DataUsageReport
import com.bright.onepluscare.data.UsagePeriod
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DataUsageLocal(
    val period: UsagePeriod = UsagePeriod.TODAY,
    val loading: Boolean = false,
    val report: DataUsageReport? = null,
    val failed: Boolean = false,
    val showSystem: Boolean = false,
)

data class DataUsageUiState(val local: DataUsageLocal = DataUsageLocal(), val usageAccess: Boolean = false)

class DataUsageViewModel(private val g: AppGraph) : ViewModel() {
    private val local = MutableStateFlow(DataUsageLocal())
    private var job: Job? = null

    val state: StateFlow<DataUsageUiState> = combine(local, g.capabilities) { l, c -> DataUsageUiState(l, c.usageAccess) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataUsageUiState())

    fun load(period: UsagePeriod = local.value.period) {
        job?.cancel()
        local.update { it.copy(period = period, loading = true, failed = false) }
        job = viewModelScope.launch {
            val r = g.dataUsage.load(period)
            local.update { it.copy(loading = false, report = r, failed = r == null) }
        }
    }

    fun setShowSystem(on: Boolean) = local.update { it.copy(showSystem = on) }

    fun openApp(pkg: String) = g.intents.openAppDetails(pkg)

    /** Adds the app to the firewall for that network (the user turns the firewall on from its screen). */
    fun block(pkg: String, kind: NetKind) {
        g.firewall.set(pkg, kind, true)
    }
}
