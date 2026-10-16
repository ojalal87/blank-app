package com.bright.onepluscare.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bright.onepluscare.AppGraph
import com.bright.onepluscare.R
import com.bright.onepluscare.core.diagnostics.DiagnosticOptions
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.settings.AppSettings
import com.bright.onepluscare.core.settings.NotificationLevel
import com.bright.onepluscare.core.storage.ExclusionRules
import com.bright.onepluscare.data.SystemIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExportState(val building: Boolean = false, val pendingContent: String? = null, val launched: Boolean = false, val message: String? = null)

class SettingsViewModel(private val g: AppGraph) : ViewModel() {
    val settings: StateFlow<AppSettings> = g.settings.settings
    val capabilities = g.capabilities
    private val _export = MutableStateFlow(ExportState())
    val export: StateFlow<ExportState> = _export.asStateFlow()

    fun completeOnboarding() = g.settings.update { it.copy(onboardingDone = true) }

    fun setDailyScan(enabled: Boolean) {
        g.settings.update { it.copy(dailyScanEnabled = enabled) }
        g.scheduler.apply(enabled)
        g.history.add(HistoryType.SCHEDULE, detail = if (enabled) "on" else "off")
    }

    fun setNotificationLevel(level: NotificationLevel) = g.settings.update { it.copy(notificationLevel = level) }
    fun setLargeFileMb(mb: Int) = g.settings.update { it.copy(largeFileMinMb = mb) }
    fun setLargeFileAge(days: Int) = g.settings.update { it.copy(largeFileMinAgeDays = days) }
    fun setRetention(days: Int?) = g.settings.update { it.copy(trashRetentionDays = days) }
    fun setDiagnostics(transform: (DiagnosticOptions) -> DiagnosticOptions) = g.settings.update { it.copy(diagnostics = transform(it.diagnostics)) }

    /** Returns false when the entry is empty or tries to escape (e.g. ".."). */
    fun addExclusion(raw: String): Boolean {
        val normalized = ExclusionRules.normalize(raw) ?: return false
        g.settings.update { it.copy(exclusions = it.exclusions + normalized) }
        return true
    }

    fun removeExclusion(value: String) = g.settings.update { it.copy(exclusions = it.exclusions - value) }

    fun open(target: SystemIntents.Target) = g.intents.open(target)

    fun refreshCapabilities() = g.refreshCapabilities()

    /** Step 1: build the report in memory. Step 2 (UI): user picks a destination with the system picker. */
    fun prepareExport() {
        if (_export.value.building) return
        viewModelScope.launch {
            _export.update { it.copy(building = true) }
            val options = g.settings.settings.value.diagnostics
            val advanced = if (options.includeAdvanced) g.shizuku.collect() else null
            val content = g.diagnostics.build(options, g.capabilities.value, g.results.value.network, advanced)
            _export.update { it.copy(building = false, pendingContent = content, launched = false) }
        }
    }

    fun exportLaunched() = _export.update { it.copy(launched = true) }

    fun exportDestinationChosen(uri: Uri?) {
        val content = _export.value.pendingContent
        _export.update { it.copy(pendingContent = null, launched = false) }
        if (uri == null || content == null) return
        viewModelScope.launch {
            val ok = g.diagnostics.write(uri, content)
            _export.update { it.copy(message = g.appContext.getString(if (ok) R.string.msg_export_done else R.string.msg_export_failed)) }
        }
    }

    fun exportMessageShown() = _export.update { it.copy(message = null) }
}
