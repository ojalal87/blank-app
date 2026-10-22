package com.bright.onepluscare.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bright.onepluscare.R
import com.bright.onepluscare.core.apps.AppFlag
import com.bright.onepluscare.core.apps.SensitivePermission
import com.bright.onepluscare.core.battery.ThermalStatusLabel
import com.bright.onepluscare.core.health.HealthReason
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.model.BatteryHealth
import com.bright.onepluscare.core.model.ChargeState
import com.bright.onepluscare.core.model.PlugType
import com.bright.onepluscare.core.network.LayerStatus
import com.bright.onepluscare.core.network.NetworkLayer
import com.bright.onepluscare.core.network.NetworkVerdict
import com.bright.onepluscare.core.oem.GuidanceItem
import com.bright.onepluscare.core.rules.Evidence
import com.bright.onepluscare.core.rules.EvidenceKind
import com.bright.onepluscare.core.rules.RecommendationId
import com.bright.onepluscare.core.rules.RequiredPermission
import com.bright.onepluscare.core.rules.Reversibility
import com.bright.onepluscare.core.rules.RiskLevel
import com.bright.onepluscare.core.rules.UserAction
import com.bright.onepluscare.core.storage.CleanupCategory
import com.bright.onepluscare.core.storage.StorageAccessMode

/** All enum -> localized text mapping lives here so screens stay declarative. */
object Labels {
    @StringRes fun recTitle(id: RecommendationId): Int = when (id) {
        RecommendationId.STORAGE_CRITICAL -> R.string.rec_storage_critical_title
        RecommendationId.STORAGE_LOW -> R.string.rec_storage_low_title
        RecommendationId.BATTERY_HEALTH_BAD -> R.string.rec_battery_health_title
        RecommendationId.BATTERY_HOT_CHARGING -> R.string.rec_battery_hot_charging_title
        RecommendationId.BATTERY_HOT -> R.string.rec_battery_hot_title
        RecommendationId.BATTERY_WARM -> R.string.rec_battery_warm_title
        RecommendationId.THERMAL_SEVERE -> R.string.rec_thermal_severe_title
        RecommendationId.THERMAL_MODERATE -> R.string.rec_thermal_moderate_title
        RecommendationId.NETWORK_PROBLEM -> R.string.rec_network_title
        RecommendationId.CLEANUP_AVAILABLE -> R.string.rec_cleanup_title
        RecommendationId.DUPLICATES_AVAILABLE -> R.string.rec_duplicates_title
        RecommendationId.TRASH_HOLDING_SPACE -> R.string.rec_trash_title
        RecommendationId.STORAGE_PERMISSION_MISSING -> R.string.rec_storage_perm_title
        RecommendationId.USAGE_ACCESS_MISSING -> R.string.rec_usage_access_title
        RecommendationId.UNUSED_APPS -> R.string.rec_unused_apps_title
        RecommendationId.SENSITIVE_UNUSED_APPS -> R.string.rec_sensitive_unused_title
        RecommendationId.MANY_BATTERY_EXEMPTIONS -> R.string.rec_battery_exempt_title
        RecommendationId.SECURITY_PATCH_OLD -> R.string.rec_patch_title
        RecommendationId.SYSTEM_LOW_MEMORY -> R.string.rec_low_memory_title
    }

    @StringRes fun recReason(id: RecommendationId): Int = when (id) {
        RecommendationId.STORAGE_CRITICAL -> R.string.rec_storage_critical_reason
        RecommendationId.STORAGE_LOW -> R.string.rec_storage_low_reason
        RecommendationId.BATTERY_HEALTH_BAD -> R.string.rec_battery_health_reason
        RecommendationId.BATTERY_HOT_CHARGING -> R.string.rec_battery_hot_charging_reason
        RecommendationId.BATTERY_HOT -> R.string.rec_battery_hot_reason
        RecommendationId.BATTERY_WARM -> R.string.rec_battery_warm_reason
        RecommendationId.THERMAL_SEVERE -> R.string.rec_thermal_severe_reason
        RecommendationId.THERMAL_MODERATE -> R.string.rec_thermal_moderate_reason
        RecommendationId.NETWORK_PROBLEM -> R.string.rec_network_reason
        RecommendationId.CLEANUP_AVAILABLE -> R.string.rec_cleanup_reason
        RecommendationId.DUPLICATES_AVAILABLE -> R.string.rec_duplicates_reason
        RecommendationId.TRASH_HOLDING_SPACE -> R.string.rec_trash_reason
        RecommendationId.STORAGE_PERMISSION_MISSING -> R.string.rec_storage_perm_reason
        RecommendationId.USAGE_ACCESS_MISSING -> R.string.rec_usage_access_reason
        RecommendationId.UNUSED_APPS -> R.string.rec_unused_apps_reason
        RecommendationId.SENSITIVE_UNUSED_APPS -> R.string.rec_sensitive_unused_reason
        RecommendationId.MANY_BATTERY_EXEMPTIONS -> R.string.rec_battery_exempt_reason
        RecommendationId.SECURITY_PATCH_OLD -> R.string.rec_patch_reason
        RecommendationId.SYSTEM_LOW_MEMORY -> R.string.rec_low_memory_reason
    }

    @StringRes fun recBenefit(id: RecommendationId): Int = when (id) {
        RecommendationId.STORAGE_CRITICAL, RecommendationId.STORAGE_LOW -> R.string.rec_benefit_storage
        RecommendationId.BATTERY_HEALTH_BAD -> R.string.rec_benefit_battery_health
        RecommendationId.BATTERY_HOT_CHARGING, RecommendationId.BATTERY_HOT, RecommendationId.BATTERY_WARM -> R.string.rec_benefit_battery_temp
        RecommendationId.THERMAL_SEVERE, RecommendationId.THERMAL_MODERATE -> R.string.rec_benefit_thermal
        RecommendationId.NETWORK_PROBLEM -> R.string.rec_benefit_network
        RecommendationId.CLEANUP_AVAILABLE, RecommendationId.DUPLICATES_AVAILABLE -> R.string.rec_benefit_cleanup
        RecommendationId.TRASH_HOLDING_SPACE -> R.string.rec_benefit_trash
        RecommendationId.STORAGE_PERMISSION_MISSING -> R.string.rec_benefit_storage_perm
        RecommendationId.USAGE_ACCESS_MISSING -> R.string.rec_benefit_usage_access
        RecommendationId.UNUSED_APPS -> R.string.rec_benefit_unused_apps
        RecommendationId.SENSITIVE_UNUSED_APPS -> R.string.rec_benefit_privacy
        RecommendationId.MANY_BATTERY_EXEMPTIONS -> R.string.rec_benefit_battery_exempt
        RecommendationId.SECURITY_PATCH_OLD -> R.string.rec_benefit_patch
        RecommendationId.SYSTEM_LOW_MEMORY -> R.string.rec_benefit_low_memory
    }

    @StringRes fun action(a: UserAction): Int = when (a) {
        UserAction.RUN_SMART_SCAN -> R.string.action_run_smart_scan
        UserAction.OPEN_CLEANER -> R.string.action_review_files
        UserAction.OPEN_DUPLICATES -> R.string.action_review_duplicates
        UserAction.OPEN_TRASH -> R.string.action_open_trash
        UserAction.OPEN_APP_DOCTOR -> R.string.action_open_app_doctor
        UserAction.OPEN_PRIVACY -> R.string.action_open_privacy
        UserAction.OPEN_BATTERY -> R.string.action_open_battery
        UserAction.OPEN_NETWORK -> R.string.action_open_network
        UserAction.GRANT_STORAGE -> R.string.action_grant_storage
        UserAction.GRANT_USAGE_ACCESS -> R.string.action_grant_usage
        UserAction.OPEN_SYSTEM_SETTINGS -> R.string.action_open_system_update
    }

    @StringRes fun risk(r: RiskLevel): Int = when (r) {
        RiskLevel.NONE -> R.string.risk_none
        RiskLevel.LOW -> R.string.risk_low
        RiskLevel.MEDIUM -> R.string.risk_medium
        RiskLevel.HIGH -> R.string.risk_high
    }

    @StringRes fun reversibility(r: Reversibility): Int = when (r) {
        Reversibility.NOT_APPLICABLE -> R.string.rev_na
        Reversibility.REVERSIBLE -> R.string.rev_yes
        Reversibility.PARTIALLY_REVERSIBLE -> R.string.rev_partial
        Reversibility.IRREVERSIBLE -> R.string.rev_no
    }

    @StringRes fun permission(p: RequiredPermission): Int = when (p) {
        RequiredPermission.NONE -> R.string.perm_none
        RequiredPermission.STORAGE -> R.string.perm_storage
        RequiredPermission.USAGE_ACCESS -> R.string.perm_usage
        RequiredPermission.NOTIFICATIONS -> R.string.perm_notifications
        RequiredPermission.SHIZUKU_OPTIONAL -> R.string.perm_shizuku
    }

    @StringRes fun evidence(kind: EvidenceKind): Int = when (kind) {
        EvidenceKind.FREE_SPACE -> R.string.ev_free_space
        EvidenceKind.FREE_PERCENT -> R.string.ev_free_percent
        EvidenceKind.BATTERY_TEMP -> R.string.ev_battery_temp
        EvidenceKind.BATTERY_HEALTH -> R.string.ev_battery_health
        EvidenceKind.CHARGE_STATE -> R.string.ev_charge_state
        EvidenceKind.THERMAL_STATUS -> R.string.ev_thermal_status
        EvidenceKind.CANDIDATE_BYTES -> R.string.ev_candidate_bytes
        EvidenceKind.CANDIDATE_COUNT -> R.string.ev_candidate_count
        EvidenceKind.SAFE_BYTES -> R.string.ev_safe_bytes
        EvidenceKind.DUPLICATE_BYTES -> R.string.ev_duplicate_bytes
        EvidenceKind.DUPLICATE_GROUPS -> R.string.ev_duplicate_groups
        EvidenceKind.TRASH_BYTES -> R.string.ev_trash_bytes
        EvidenceKind.TRASH_COUNT -> R.string.ev_trash_count
        EvidenceKind.UNUSED_APPS -> R.string.ev_unused_apps
        EvidenceKind.SENSITIVE_UNUSED_APPS -> R.string.ev_sensitive_unused
        EvidenceKind.BATTERY_EXEMPT_APPS -> R.string.ev_battery_exempt
        EvidenceKind.SECURITY_PATCH_AGE_DAYS -> R.string.ev_patch_age
        EvidenceKind.NETWORK_VERDICT -> R.string.ev_network_verdict
        EvidenceKind.LOW_MEMORY -> R.string.ev_low_memory
    }

    @StringRes fun healthReason(r: HealthReason): Int = when (r) {
        HealthReason.STORAGE_OK -> R.string.hr_storage_ok
        HealthReason.STORAGE_LOW -> R.string.hr_storage_low
        HealthReason.STORAGE_CRITICAL -> R.string.hr_storage_critical
        HealthReason.BATTERY_TEMP_OK -> R.string.hr_battery_ok
        HealthReason.BATTERY_TEMP_WARM -> R.string.hr_battery_warm
        HealthReason.BATTERY_TEMP_HOT -> R.string.hr_battery_hot
        HealthReason.BATTERY_TEMP_UNAVAILABLE -> R.string.hr_battery_unavailable
        HealthReason.BATTERY_HEALTH_REPORTED_BAD -> R.string.hr_battery_bad
        HealthReason.THERMAL_OK -> R.string.hr_thermal_ok
        HealthReason.THERMAL_MODERATE -> R.string.hr_thermal_moderate
        HealthReason.THERMAL_SEVERE -> R.string.hr_thermal_severe
        HealthReason.THERMAL_UNAVAILABLE -> R.string.hr_thermal_unavailable
        HealthReason.MEMORY_OK -> R.string.hr_memory_ok
        HealthReason.MEMORY_SYSTEM_LOW -> R.string.hr_memory_low
        HealthReason.NETWORK_OK -> R.string.hr_network_ok
        HealthReason.NETWORK_OFFLINE -> R.string.hr_network_offline
        HealthReason.NETWORK_NOT_VALIDATED -> R.string.hr_network_not_validated
        HealthReason.NETWORK_CAPTIVE_PORTAL -> R.string.hr_network_captive
    }

    @StringRes fun verdict(v: NetworkVerdict): Int = when (v) {
        NetworkVerdict.OFFLINE -> R.string.nv_offline
        NetworkVerdict.CAPTIVE_PORTAL -> R.string.nv_captive
        NetworkVerdict.LOCAL_NETWORK_ONLY -> R.string.nv_local_only
        NetworkVerdict.PRIVATE_DNS_FAILURE -> R.string.nv_private_dns
        NetworkVerdict.DNS_FAILURE -> R.string.nv_dns
        NetworkVerdict.INTERNET_UNREACHABLE -> R.string.nv_unreachable
        NetworkVerdict.VPN_SUSPECTED -> R.string.nv_vpn
        NetworkVerdict.NOT_VALIDATED -> R.string.nv_not_validated
        NetworkVerdict.SLOW -> R.string.nv_slow
        NetworkVerdict.HEALTHY -> R.string.nv_healthy
    }

    @StringRes fun verdictAdvice(v: NetworkVerdict): Int = when (v) {
        NetworkVerdict.OFFLINE -> R.string.nva_offline
        NetworkVerdict.CAPTIVE_PORTAL -> R.string.nva_captive
        NetworkVerdict.LOCAL_NETWORK_ONLY -> R.string.nva_local_only
        NetworkVerdict.PRIVATE_DNS_FAILURE -> R.string.nva_private_dns
        NetworkVerdict.DNS_FAILURE -> R.string.nva_dns
        NetworkVerdict.INTERNET_UNREACHABLE -> R.string.nva_unreachable
        NetworkVerdict.VPN_SUSPECTED -> R.string.nva_vpn
        NetworkVerdict.NOT_VALIDATED -> R.string.nva_not_validated
        NetworkVerdict.SLOW -> R.string.nva_slow
        NetworkVerdict.HEALTHY -> R.string.nva_healthy
    }

    @StringRes fun layer(l: NetworkLayer): Int = when (l) {
        NetworkLayer.LINK -> R.string.nl_link
        NetworkLayer.GATEWAY -> R.string.nl_gateway
        NetworkLayer.DNS -> R.string.nl_dns
        NetworkLayer.INTERNET -> R.string.nl_internet
        NetworkLayer.VALIDATION -> R.string.nl_validation
        NetworkLayer.VPN -> R.string.nl_vpn
        NetworkLayer.PRIVATE_DNS -> R.string.nl_private_dns
    }

    @StringRes fun layerStatus(s: LayerStatus): Int = when (s) {
        LayerStatus.OK -> R.string.ls_ok
        LayerStatus.PROBLEM -> R.string.ls_problem
        LayerStatus.UNKNOWN -> R.string.ls_unknown
        LayerStatus.NOT_APPLICABLE -> R.string.ls_na
    }

    @StringRes fun category(c: CleanupCategory): Int = when (c) {
        CleanupCategory.TEMP_FILE -> R.string.cat_temp
        CleanupCategory.INSTALLER_APK -> R.string.cat_apk
        CleanupCategory.OLD_DOWNLOAD -> R.string.cat_old_download
        CleanupCategory.OLD_SCREENSHOT -> R.string.cat_old_screenshot
        CleanupCategory.LARGE_FILE -> R.string.cat_large
    }

    @StringRes fun trashCategory(raw: String): Int = when (raw) {
        "TEMP_FILE" -> R.string.cat_temp
        "INSTALLER_APK" -> R.string.cat_apk
        "OLD_DOWNLOAD" -> R.string.cat_old_download
        "OLD_SCREENSHOT" -> R.string.cat_old_screenshot
        "LARGE_FILE" -> R.string.cat_large
        "DUPLICATE" -> R.string.cat_duplicate
        "recovered" -> R.string.cat_recovered
        else -> R.string.cat_other
    }

    @StringRes fun history(t: HistoryType): Int = when (t) {
        HistoryType.SCAN -> R.string.ht_scan
        HistoryType.DUPLICATE_SCAN -> R.string.ht_duplicate_scan
        HistoryType.SCHEDULED_SCAN -> R.string.ht_scheduled_scan
        HistoryType.TRASH -> R.string.ht_trash
        HistoryType.SYSTEM_TRASH -> R.string.ht_system_trash
        HistoryType.RESTORE -> R.string.ht_restore
        HistoryType.PURGE -> R.string.ht_purge
        HistoryType.EXPIRY -> R.string.ht_expiry
        HistoryType.SCHEDULE -> R.string.ht_schedule
        HistoryType.SESSION -> R.string.ht_session
        HistoryType.NETWORK -> R.string.ht_network
        HistoryType.EXPORT -> R.string.ht_export
        HistoryType.RECOVERY -> R.string.ht_recovery
        HistoryType.TWEAK -> R.string.ht_tweak
        HistoryType.TWEAK_UNDO -> R.string.ht_tweak_undo
        HistoryType.DEBLOAT -> R.string.ht_debloat
        HistoryType.OTHER -> R.string.ht_other
    }

    @StringRes fun thermal(l: ThermalStatusLabel): Int = when (l) {
        ThermalStatusLabel.NONE -> R.string.th_none
        ThermalStatusLabel.LIGHT -> R.string.th_light
        ThermalStatusLabel.MODERATE -> R.string.th_moderate
        ThermalStatusLabel.SEVERE -> R.string.th_severe
        ThermalStatusLabel.CRITICAL -> R.string.th_critical
        ThermalStatusLabel.EMERGENCY -> R.string.th_emergency
        ThermalStatusLabel.SHUTDOWN -> R.string.th_shutdown
        ThermalStatusLabel.UNKNOWN -> R.string.not_available
    }

    @StringRes fun charge(c: ChargeState): Int = when (c) {
        ChargeState.CHARGING -> R.string.cs_charging
        ChargeState.DISCHARGING -> R.string.cs_discharging
        ChargeState.FULL -> R.string.cs_full
        ChargeState.NOT_CHARGING -> R.string.cs_not_charging
        ChargeState.UNKNOWN -> R.string.not_available
    }

    @StringRes fun plug(p: PlugType): Int = when (p) {
        PlugType.AC -> R.string.plug_ac
        PlugType.USB -> R.string.plug_usb
        PlugType.WIRELESS -> R.string.plug_wireless
        PlugType.DOCK -> R.string.plug_dock
        PlugType.NONE -> R.string.plug_none
        PlugType.UNKNOWN -> R.string.not_available
    }

    @StringRes fun batteryHealth(h: BatteryHealth): Int = when (h) {
        BatteryHealth.GOOD -> R.string.bh_good
        BatteryHealth.OVERHEAT -> R.string.bh_overheat
        BatteryHealth.DEAD -> R.string.bh_dead
        BatteryHealth.OVER_VOLTAGE -> R.string.bh_over_voltage
        BatteryHealth.COLD -> R.string.bh_cold
        BatteryHealth.FAILURE -> R.string.bh_failure
        BatteryHealth.UNKNOWN -> R.string.not_available
    }

    @StringRes fun sensitive(p: SensitivePermission): Int = when (p) {
        SensitivePermission.CAMERA -> R.string.sp_camera
        SensitivePermission.MICROPHONE -> R.string.sp_microphone
        SensitivePermission.LOCATION -> R.string.sp_location
        SensitivePermission.BACKGROUND_LOCATION -> R.string.sp_background_location
        SensitivePermission.CONTACTS -> R.string.sp_contacts
        SensitivePermission.CALL_LOG -> R.string.sp_call_log
        SensitivePermission.SMS -> R.string.sp_sms
        SensitivePermission.PHONE -> R.string.sp_phone
        SensitivePermission.BODY_SENSORS -> R.string.sp_body_sensors
        SensitivePermission.NEARBY_DEVICES -> R.string.sp_nearby
    }

    @StringRes fun appFlag(f: AppFlag): Int = when (f) {
        AppFlag.UNUSED_LONG -> R.string.af_unused
        AppFlag.LARGE_CACHE -> R.string.af_large_cache
        AppFlag.HEAVY_USE -> R.string.af_heavy_use
        AppFlag.SENSITIVE_AND_UNUSED -> R.string.af_sensitive_unused
        AppFlag.BATTERY_EXEMPT -> R.string.af_battery_exempt
    }

    @StringRes fun guidanceTitle(g: GuidanceItem): Int = when (g) {
        GuidanceItem.REVIEW_BATTERY_OPTIMIZATION_EXEMPTIONS -> R.string.g_exemptions_title
        GuidanceItem.REVIEW_BATTERY_USAGE -> R.string.g_usage_title
        GuidanceItem.PER_APP_BACKGROUND_ACTIVITY -> R.string.g_background_title
        GuidanceItem.OPTIMIZED_CHARGING_INFO -> R.string.g_charging_title
        GuidanceItem.SCHEDULED_SCAN_RELIABILITY -> R.string.g_schedule_title
        GuidanceItem.STORAGE_SETTINGS -> R.string.g_storage_title
    }

    @StringRes fun guidanceBody(g: GuidanceItem): Int = when (g) {
        GuidanceItem.REVIEW_BATTERY_OPTIMIZATION_EXEMPTIONS -> R.string.g_exemptions_body
        GuidanceItem.REVIEW_BATTERY_USAGE -> R.string.g_usage_body
        GuidanceItem.PER_APP_BACKGROUND_ACTIVITY -> R.string.g_background_body
        GuidanceItem.OPTIMIZED_CHARGING_INFO -> R.string.g_charging_body
        GuidanceItem.SCHEDULED_SCAN_RELIABILITY -> R.string.g_schedule_body
        GuidanceItem.STORAGE_SETTINGS -> R.string.g_storage_body
    }

    @StringRes fun transport(t: com.bright.onepluscare.core.model.Transport): Int = when (t) {
        com.bright.onepluscare.core.model.Transport.WIFI -> R.string.tr_wifi
        com.bright.onepluscare.core.model.Transport.CELLULAR -> R.string.tr_cellular
        com.bright.onepluscare.core.model.Transport.ETHERNET -> R.string.tr_ethernet
        com.bright.onepluscare.core.model.Transport.VPN -> R.string.tr_vpn
        com.bright.onepluscare.core.model.Transport.BLUETOOTH -> R.string.tr_bluetooth
        com.bright.onepluscare.core.model.Transport.OTHER -> R.string.tr_other
    }

    @StringRes fun storageMode(m: StorageAccessMode): Int = when (m) {
        StorageAccessMode.FILESYSTEM -> R.string.sm_filesystem
        StorageAccessMode.MEDIASTORE -> R.string.sm_mediastore
        StorageAccessMode.MEDIASTORE_PARTIAL -> R.string.sm_partial
        StorageAccessMode.NONE -> R.string.sm_none
    }
}

/** Localized value text for one piece of evidence. */
@Composable
fun evidenceValue(e: Evidence): String {
    val context = LocalContext.current
    return when (e) {
        is Evidence.Bytes -> Fmt.bytes(context, e.bytes)
        is Evidence.Percent -> Fmt.percent(e.percent, 1)
        is Evidence.Celsius -> celsiusText(e.celsius)
        is Evidence.Count -> if (e.kind == EvidenceKind.THERMAL_STATUS) {
            stringResource(Labels.thermal(com.bright.onepluscare.core.battery.BatteryInterpreter.thermalLabel(e.count)))
        } else {
            Fmt.number(e.count)
        }
        is Evidence.Days -> daysText(e.days)
        is Evidence.Status -> when (e.kind) {
            EvidenceKind.NETWORK_VERDICT -> runCatching { NetworkVerdict.valueOf(e.value) }.getOrNull()?.let { stringResource(Labels.verdict(it)) } ?: e.value
            EvidenceKind.BATTERY_HEALTH -> runCatching { BatteryHealth.valueOf(e.value) }.getOrNull()?.let { stringResource(Labels.batteryHealth(it)) } ?: e.value
            EvidenceKind.CHARGE_STATE -> runCatching { ChargeState.valueOf(e.value) }.getOrNull()?.let { stringResource(Labels.charge(it)) } ?: e.value
            EvidenceKind.LOW_MEMORY -> stringResource(R.string.ev_low_memory_value)
            else -> e.value
        }
    }
}
