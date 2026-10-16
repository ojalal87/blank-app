package com.bright.onepluscare.data

import android.content.Context
import com.bright.onepluscare.core.diagnostics.DiagnosticOptions
import com.bright.onepluscare.core.settings.AppSettings
import com.bright.onepluscare.core.settings.NotificationLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences. This is the only file included in Android backup (see data_extraction_rules.xml). */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<AppSettings> = state.asStateFlow()

    /** Re-read from disk (used once after the v0.3 migration wrote preferences directly). */
    fun reload() {
        state.value = read()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        synchronized(this) {
            val next = transform(state.value).sanitized()
            write(next)
            state.value = next
        }
    }

    private fun read(): AppSettings = AppSettings(
        onboardingDone = prefs.getBoolean(K_ONBOARDING, false),
        dailyScanEnabled = prefs.getBoolean(K_DAILY, false),
        notificationLevel = runCatching { NotificationLevel.valueOf(prefs.getString(K_NOTIFY, null) ?: "") }.getOrDefault(NotificationLevel.IMPORTANT),
        trashRetentionDays = prefs.getInt(K_RETENTION, -1).takeIf { it > 0 },
        largeFileMinMb = prefs.getInt(K_LARGE_MB, 250),
        largeFileMinAgeDays = prefs.getInt(K_LARGE_AGE, 0),
        exclusions = prefs.getString(K_EXCLUSIONS, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
        diagnostics = DiagnosticOptions(
            includeDeviceModel = prefs.getBoolean(K_DIAG_MODEL, true),
            includeNetworkLayers = prefs.getBoolean(K_DIAG_NET, false),
            includeAdvanced = prefs.getBoolean(K_DIAG_ADV, false),
            includeHistorySummary = prefs.getBoolean(K_DIAG_HIST, false),
        ),
    ).sanitized()

    private fun write(s: AppSettings) {
        // commit(): settings changes are rare and must not be lost if the process is killed right after.
        prefs.edit()
            .putBoolean(K_ONBOARDING, s.onboardingDone)
            .putBoolean(K_DAILY, s.dailyScanEnabled)
            .putString(K_NOTIFY, s.notificationLevel.name)
            .putInt(K_RETENTION, s.trashRetentionDays ?: -1)
            .putInt(K_LARGE_MB, s.largeFileMinMb)
            .putInt(K_LARGE_AGE, s.largeFileMinAgeDays)
            .putString(K_EXCLUSIONS, s.exclusions.joinToString("\n"))
            .putBoolean(K_DIAG_MODEL, s.diagnostics.includeDeviceModel)
            .putBoolean(K_DIAG_NET, s.diagnostics.includeNetworkLayers)
            .putBoolean(K_DIAG_ADV, s.diagnostics.includeAdvanced)
            .putBoolean(K_DIAG_HIST, s.diagnostics.includeHistorySummary)
            .commit()
    }

    companion object {
        const val FILE = "care_settings"
        private const val K_ONBOARDING = "onboarding_done"
        private const val K_DAILY = "daily_scan"
        private const val K_NOTIFY = "notification_level"
        private const val K_RETENTION = "trash_retention_days"
        private const val K_LARGE_MB = "large_file_mb"
        private const val K_LARGE_AGE = "large_file_age_days"
        private const val K_EXCLUSIONS = "exclusions"
        private const val K_DIAG_MODEL = "diag_model"
        private const val K_DIAG_NET = "diag_network"
        private const val K_DIAG_ADV = "diag_advanced"
        private const val K_DIAG_HIST = "diag_history"
    }
}
