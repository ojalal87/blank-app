package com.bright.onepluscare.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.data.SystemIntents
import com.bright.onepluscare.shizuku.AdvancedReport
import com.bright.onepluscare.shizuku.ShizukuStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdvancedViewModel(private val g: AppGraph) : ViewModel() {
    val status: StateFlow<ShizukuStatus> = g.shizuku.status
    private val _report = MutableStateFlow<AdvancedReport?>(null)
    val report: StateFlow<AdvancedReport?> = _report.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun refresh() = g.shizuku.refresh()
    fun requestPermission() = g.shizuku.requestPermission()
    fun openShizuku() = g.intents.open(SystemIntents.Target.SHIZUKU_APP)

    fun collect() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                if (!g.shizuku.status.value.serviceConnected) {
                    g.shizuku.bind()
                    // Binding is asynchronous; wait briefly for onServiceConnected.
                    repeat(20) {
                        if (g.shizuku.status.value.serviceConnected) return@repeat
                        delay(150)
                    }
                }
                _report.value = g.shizuku.collect()
            } finally {
                _busy.value = false
            }
        }
    }

    fun stopService() {
        g.shizuku.unbind()
        _report.value = null
    }
}
