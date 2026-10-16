package com.bright.onepluscare.core.oem

import java.util.Locale

enum class OemFamily {
    /** OnePlus (OxygenOS; shares the ColorOS code base since OxygenOS 12). */
    ONEPLUS,

    /** OPPO / realme (ColorOS / realme UI): same battery-management behaviour family as OxygenOS. */
    OPPO_FAMILY,
    SAMSUNG,
    XIAOMI,
    GOOGLE,
    OTHER,
}

/** Guidance cards. Each maps to a documented android.provider.Settings action in the app layer. */
enum class GuidanceItem {
    /** Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS: review which apps are exempt. */
    REVIEW_BATTERY_OPTIMIZATION_EXEMPTIONS,

    /** Intent.ACTION_POWER_USAGE_SUMMARY: per-app battery usage. */
    REVIEW_BATTERY_USAGE,

    /** Settings.ACTION_APPLICATION_DETAILS_SETTINGS: per-app "Battery" -> background activity on OxygenOS. */
    PER_APP_BACKGROUND_ACTIVITY,

    /** OxygenOS/ColorOS ship an optimized-charging option under Battery settings; we can only point there. */
    OPTIMIZED_CHARGING_INFO,

    /** Explain that OxygenOS may stop background work aggressively, which can delay this app's scheduled scan. */
    SCHEDULED_SCAN_RELIABILITY,

    /** Settings.ACTION_INTERNAL_STORAGE_SETTINGS. */
    STORAGE_SETTINGS,
}

data class OemProfile(
    val family: OemFamily,
    val guidance: List<GuidanceItem>,
    /** True when the OEM is known to apply extra background restrictions beyond AOSP Doze/App Standby. */
    val aggressiveBackgroundPolicy: Boolean,
)

object OemDetector {
    fun detect(manufacturer: String?, brand: String?): OemProfile {
        val m = manufacturer.orEmpty().lowercase(Locale.ROOT).trim()
        val b = brand.orEmpty().lowercase(Locale.ROOT).trim()
        val family = when {
            m == "oneplus" || b == "oneplus" -> OemFamily.ONEPLUS
            m == "oppo" || b == "oppo" || m == "realme" || b == "realme" -> OemFamily.OPPO_FAMILY
            m == "samsung" || b == "samsung" -> OemFamily.SAMSUNG
            m == "xiaomi" || b in setOf("xiaomi", "redmi", "poco") -> OemFamily.XIAOMI
            m == "google" || b == "google" -> OemFamily.GOOGLE
            else -> OemFamily.OTHER
        }
        val common = listOf(
            GuidanceItem.REVIEW_BATTERY_USAGE,
            GuidanceItem.REVIEW_BATTERY_OPTIMIZATION_EXEMPTIONS,
            GuidanceItem.STORAGE_SETTINGS,
        )
        val guidance = when (family) {
            OemFamily.ONEPLUS, OemFamily.OPPO_FAMILY -> listOf(
                GuidanceItem.REVIEW_BATTERY_USAGE,
                GuidanceItem.PER_APP_BACKGROUND_ACTIVITY,
                GuidanceItem.REVIEW_BATTERY_OPTIMIZATION_EXEMPTIONS,
                GuidanceItem.OPTIMIZED_CHARGING_INFO,
                GuidanceItem.SCHEDULED_SCAN_RELIABILITY,
                GuidanceItem.STORAGE_SETTINGS,
            )
            OemFamily.SAMSUNG, OemFamily.XIAOMI -> common + GuidanceItem.PER_APP_BACKGROUND_ACTIVITY + GuidanceItem.SCHEDULED_SCAN_RELIABILITY
            else -> common
        }
        val aggressive = family == OemFamily.ONEPLUS || family == OemFamily.OPPO_FAMILY || family == OemFamily.XIAOMI || family == OemFamily.SAMSUNG
        return OemProfile(family, guidance, aggressive)
    }
}
