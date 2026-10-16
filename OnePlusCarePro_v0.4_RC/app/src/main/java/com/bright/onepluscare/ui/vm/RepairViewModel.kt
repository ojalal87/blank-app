package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.session.MaintenanceSession
import com.bright.onepluscare.core.session.SessionResult
import com.bright.onepluscare.data.SystemIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RepairViewModel(private val g: AppGraph) : ViewModel() {
    val session: StateFlow<MaintenanceSession?> = g.results.map { it.session }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), g.results.value.session)
    private val _result = MutableStateFlow<SessionResult?>(null)
    val result: StateFlow<SessionResult?> = _result.asStateFlow()
    val oem = g.capabilities.value.oem

    fun start() {
        viewModelScope.launch {
            val facts = g.device.facts()
            _result.value = null
            g.updateResults { it.copy(session = MaintenanceSession(facts.capturedAtMillis, facts.storage.availableBytes)) }
        }
    }

    fun finish() {
        val s = g.results.value.session ?: return
        viewModelScope.launch {
            val facts = g.device.facts()
            val r = s.finish(facts.storage.availableBytes, facts.capturedAtMillis)
            _result.value = r
            g.updateResults { it.copy(session = null) }
            g.history.add(HistoryType.SESSION, count = r.trashedCount, bytes = r.attributedFreedBytes)
        }
    }

    fun open(target: SystemIntents.Target) = g.intents.open(target)
    fun openGuidance(item: com.bright.onepluscare.core.oem.GuidanceItem) = g.intents.openGuidance(item)
}
