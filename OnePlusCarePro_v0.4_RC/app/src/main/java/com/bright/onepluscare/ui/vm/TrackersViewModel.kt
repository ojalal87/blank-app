package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.R
import com.bright.onepluscare.data.IocUpdateResult
import com.bright.onepluscare.data.TrackerReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackersUiState(
    val signatures: Int = 0,
    val updatedAtMillis: Long? = null,
    val scanning: Boolean = false,
    val progress: Float? = null,
    val includeSystem: Boolean = false,
    val report: TrackerReport? = null,
    val updating: Boolean = false,
    val message: String? = null,
)

class TrackersViewModel(private val g: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(TrackersUiState())
    val state: StateFlow<TrackersUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun load() {
        viewModelScope.launch { _state.update { it.copy(signatures = g.trackers.trackers().size, updatedAtMillis = g.trackers.updatedAtMillis()) } }
    }

    fun setIncludeSystem(on: Boolean) = _state.update { it.copy(includeSystem = on) }

    fun scan() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(scanning = true, progress = 0f) }
            try {
                val r = g.trackers.scanAll(_state.value.includeSystem) { d, t -> _state.update { it.copy(progress = if (t > 0) d.toFloat() / t else null) } }
                _state.update { it.copy(report = r) }
            } finally {
                _state.update { it.copy(scanning = false, progress = null) }
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun update() {
        if (_state.value.updating) return
        viewModelScope.launch {
            _state.update { it.copy(updating = true) }
            val r = g.trackers.update()
            val n = g.trackers.trackers().size
            _state.update {
                it.copy(
                    updating = false, signatures = n, updatedAtMillis = g.trackers.updatedAtMillis(),
                    message = g.appContext.getString(
                        when (r) {
                            IocUpdateResult.UPDATED -> R.string.tr_updated
                            IocUpdateResult.NETWORK_ERROR -> R.string.sec_ioc_network_error
                            IocUpdateResult.INVALID -> R.string.sec_ioc_invalid
                        },
                        n,
                    ),
                )
            }
        }
    }

    fun openApp(pkg: String) = g.intents.openAppDetails(pkg)
    fun messageShown() = _state.update { it.copy(message = null) }
}
