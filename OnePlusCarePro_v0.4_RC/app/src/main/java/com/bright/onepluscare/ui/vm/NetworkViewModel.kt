package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.data.NetworkResult
import com.bright.onepluscare.data.SystemIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NetworkUiState(val running: Boolean = false, val result: NetworkResult? = null)

class NetworkViewModel(private val g: AppGraph) : ViewModel() {
    private val running = MutableStateFlow(false)

    val state: StateFlow<NetworkUiState> = combine(running, g.results.map { it.network }) { r, n -> NetworkUiState(r, n) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkUiState(result = g.results.value.network))

    /** Probes contact the network only when the user taps Run; never automatically. */
    fun run() {
        if (running.value) return
        viewModelScope.launch {
            running.value = true
            try {
                val r = g.network.run()
                g.updateResults { it.copy(network = r) }
                g.history.add(HistoryType.NETWORK, detail = r.diagnosis.verdict.name.lowercase())
            } finally {
                running.value = false
            }
        }
    }

    fun open(target: SystemIntents.Target) = g.intents.open(target)
}
