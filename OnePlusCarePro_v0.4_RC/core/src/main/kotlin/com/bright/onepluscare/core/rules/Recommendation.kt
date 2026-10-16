package com.bright.onepluscare.core.rules

/** Ordinal order is display order (most severe first). */
enum class Severity { CRITICAL, ATTENTION, INFO }

enum class RiskLevel { NONE, LOW, MEDIUM, HIGH }

enum class Reversibility { NOT_APPLICABLE, REVERSIBLE, PARTIALLY_REVERSIBLE, IRREVERSIBLE }

enum class RequiredPermission { NONE, STORAGE, USAGE_ACCESS, NOTIFICATIONS, SHIZUKU_OPTIONAL }

enum class UserAction {
    RUN_SMART_SCAN, OPEN_CLEANER, OPEN_DUPLICATES, OPEN_TRASH, OPEN_APP_DOCTOR, OPEN_PRIVACY,
    OPEN_BATTERY, OPEN_NETWORK, GRANT_STORAGE, GRANT_USAGE_ACCESS, OPEN_SYSTEM_SETTINGS,
}

enum class RecommendationId {
    STORAGE_CRITICAL, STORAGE_LOW,
    BATTERY_HEALTH_BAD, BATTERY_HOT_CHARGING, BATTERY_HOT, BATTERY_WARM,
    THERMAL_SEVERE, THERMAL_MODERATE,
    NETWORK_PROBLEM,
    CLEANUP_AVAILABLE, DUPLICATES_AVAILABLE, TRASH_HOLDING_SPACE,
    STORAGE_PERMISSION_MISSING, USAGE_ACCESS_MISSING,
    UNUSED_APPS, SENSITIVE_UNUSED_APPS, MANY_BATTERY_EXEMPTIONS,
    SECURITY_PATCH_OLD,
    SYSTEM_LOW_MEMORY,
}

enum class EvidenceKind {
    FREE_SPACE, FREE_PERCENT, BATTERY_TEMP, BATTERY_HEALTH, CHARGE_STATE, THERMAL_STATUS,
    CANDIDATE_BYTES, CANDIDATE_COUNT, SAFE_BYTES, DUPLICATE_BYTES, DUPLICATE_GROUPS,
    TRASH_BYTES, TRASH_COUNT, UNUSED_APPS, SENSITIVE_UNUSED_APPS, BATTERY_EXEMPT_APPS,
    SECURITY_PATCH_AGE_DAYS, NETWORK_VERDICT, LOW_MEMORY,
}

/** A measured fact the recommendation is based on. Values are typed so the UI can localize them. */
sealed interface Evidence {
    val kind: EvidenceKind

    data class Bytes(override val kind: EvidenceKind, val bytes: Long) : Evidence
    data class Percent(override val kind: EvidenceKind, val percent: Double) : Evidence
    data class Celsius(override val kind: EvidenceKind, val celsius: Float) : Evidence
    data class Count(override val kind: EvidenceKind, val count: Int) : Evidence
    data class Days(override val kind: EvidenceKind, val days: Long) : Evidence
    /** An enum value name (e.g. a NetworkVerdict or BatteryHealth). */
    data class Status(override val kind: EvidenceKind, val value: String) : Evidence
}

/**
 * An explainable, deterministic recommendation. The reason, expected benefit and user-action text are
 * localized by the UI from [id]; everything that varies is in [evidence] and [estimatedBenefitBytes].
 * There is no "AI" here: every recommendation is a fixed rule over measured inputs.
 */
data class Recommendation(
    val id: RecommendationId,
    val severity: Severity,
    val evidence: List<Evidence>,
    /** Bytes that could be freed if the user follows the action; null when the benefit is not storage. */
    val estimatedBenefitBytes: Long?,
    val risk: RiskLevel,
    val reversibility: Reversibility,
    val permission: RequiredPermission,
    val action: UserAction,
)
