package com.bright.onepluscare.core.settings

import com.bright.onepluscare.core.diagnostics.DiagnosticOptions
import com.bright.onepluscare.core.storage.ExclusionRules
import com.bright.onepluscare.core.storage.ScanConfig
import com.bright.onepluscare.core.trash.RetentionPolicy

enum class NotificationLevel {
    /** Never notify. */
    OFF,

    /** Only when the health state is CRITICAL. */
    CRITICAL_ONLY,

    /** CRITICAL, or ATTENTION / large reclaimable space. */
    IMPORTANT,
}

data class AppSettings(
    val onboardingDone: Boolean = false,
    val dailyScanEnabled: Boolean = false,
    val notificationLevel: NotificationLevel = NotificationLevel.IMPORTANT,
    /** null = Safe Trash never expires automatically (default). */
    val trashRetentionDays: Int? = null,
    val largeFileMinMb: Int = 250,
    val largeFileMinAgeDays: Int = 0,
    val exclusions: List<String> = emptyList(),
    val diagnostics: DiagnosticOptions = DiagnosticOptions(),
) {
    fun scanConfig(): ScanConfig = ScanConfig(
        largeFileMinBytes = largeFileMinMb.toLong() * 1_000_000L,
        largeFileMinAgeDays = largeFileMinAgeDays,
        exclusions = exclusions,
    )

    /** Clamp values that may have come from an old or tampered preferences file. */
    fun sanitized(): AppSettings = copy(
        trashRetentionDays = RetentionPolicy.sanitize(trashRetentionDays),
        largeFileMinMb = largeFileMinMb.takeIf { it in LARGE_FILE_MB_OPTIONS } ?: 250,
        largeFileMinAgeDays = largeFileMinAgeDays.takeIf { it in LARGE_FILE_AGE_OPTIONS } ?: 0,
        exclusions = exclusions.mapNotNull(ExclusionRules::normalize).distinct().take(MAX_EXCLUSIONS),
    )

    companion object {
        val LARGE_FILE_MB_OPTIONS = listOf(100, 250, 500, 1000)
        val LARGE_FILE_AGE_OPTIONS = listOf(0, 30, 90, 180, 365)
        const val MAX_EXCLUSIONS = 50
    }
}
