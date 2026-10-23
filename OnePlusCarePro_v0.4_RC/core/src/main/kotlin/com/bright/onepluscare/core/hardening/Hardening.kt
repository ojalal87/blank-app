package com.bright.onepluscare.core.hardening

import com.bright.onepluscare.core.network.PrivateDnsMode

enum class WifiSecurity { OPEN, WEP, ENHANCED_OPEN, SECURED, NOT_ON_WIFI, UNKNOWN }

/** Facts read from public APIs. null = could not be read on this device (shown as "check yourself"). */
data class HardeningFacts(
    val screenLockSecure: Boolean?,
    val patchAgeDays: Long?,
    val developerOptions: Boolean?,
    val usbDebugging: Boolean?,
    val storageEncrypted: Boolean?,
    /** Lock screen shows notification contents (not just "contents hidden"). */
    val lockScreenShowsContent: Boolean?,
    /** User apps that can ask to install other apps (browsers, file managers, chat apps...). */
    val installerApps: List<String>,
    val wifi: WifiSecurity,
    val privateDns: PrivateDnsMode,
    val shizukuRunning: Boolean,
)

enum class CheckId { SCREEN_LOCK, PATCH_AGE, STORAGE_ENCRYPTION, USB_DEBUGGING, DEVELOPER_OPTIONS, LOCK_SCREEN_CONTENT, INSTALL_SOURCES, WIFI_SECURITY, PRIVATE_DNS }

enum class CheckState { OK, ATTENTION, CRITICAL, UNKNOWN }

data class HardeningCheck(val id: CheckId, val state: CheckState, val detail: String? = null)

/**
 * Explainable device-hardening checklist. Each check is a plain rule over one fact, so the UI can say
 * exactly why. No score: a list of what is fine and what to change.
 */
object HardeningEvaluator {
    const val PATCH_ATTENTION_DAYS = 90L
    const val PATCH_CRITICAL_DAYS = 180L

    fun evaluate(f: HardeningFacts): List<HardeningCheck> {
        val out = ArrayList<HardeningCheck>()
        out += HardeningCheck(CheckId.SCREEN_LOCK, when (f.screenLockSecure) { true -> CheckState.OK; false -> CheckState.CRITICAL; null -> CheckState.UNKNOWN })
        out += HardeningCheck(
            CheckId.PATCH_AGE,
            when {
                f.patchAgeDays == null -> CheckState.UNKNOWN
                f.patchAgeDays > PATCH_CRITICAL_DAYS -> CheckState.CRITICAL
                f.patchAgeDays > PATCH_ATTENTION_DAYS -> CheckState.ATTENTION
                else -> CheckState.OK
            },
            f.patchAgeDays?.toString(),
        )
        out += HardeningCheck(CheckId.STORAGE_ENCRYPTION, when (f.storageEncrypted) { true -> CheckState.OK; false -> CheckState.CRITICAL; null -> CheckState.UNKNOWN })
        // USB/wireless debugging is what Shizuku needs; flag it, but say why it may be on.
        out += HardeningCheck(
            CheckId.USB_DEBUGGING,
            when (f.usbDebugging) { true -> CheckState.ATTENTION; false -> CheckState.OK; null -> CheckState.UNKNOWN },
            if (f.usbDebugging == true && f.shizukuRunning) "shizuku" else null,
        )
        out += HardeningCheck(CheckId.DEVELOPER_OPTIONS, when (f.developerOptions) { true -> if (f.usbDebugging == true) CheckState.ATTENTION else CheckState.OK; false -> CheckState.OK; null -> CheckState.UNKNOWN })
        out += HardeningCheck(CheckId.LOCK_SCREEN_CONTENT, when (f.lockScreenShowsContent) { true -> CheckState.ATTENTION; false -> CheckState.OK; null -> CheckState.UNKNOWN })
        out += HardeningCheck(CheckId.INSTALL_SOURCES, if (f.installerApps.isEmpty()) CheckState.OK else CheckState.ATTENTION, f.installerApps.size.toString())
        out += HardeningCheck(
            CheckId.WIFI_SECURITY,
            when (f.wifi) {
                WifiSecurity.OPEN, WifiSecurity.WEP -> CheckState.CRITICAL
                WifiSecurity.ENHANCED_OPEN -> CheckState.ATTENTION
                WifiSecurity.SECURED, WifiSecurity.NOT_ON_WIFI -> CheckState.OK
                WifiSecurity.UNKNOWN -> CheckState.UNKNOWN
            },
            f.wifi.name,
        )
        out += HardeningCheck(
            CheckId.PRIVATE_DNS,
            when (f.privateDns) {
                PrivateDnsMode.STRICT, PrivateDnsMode.OPPORTUNISTIC -> CheckState.OK
                PrivateDnsMode.OFF -> CheckState.ATTENTION
                PrivateDnsMode.UNKNOWN -> CheckState.UNKNOWN
            },
            f.privateDns.name,
        )
        return out.sortedBy { when (it.state) { CheckState.CRITICAL -> 0; CheckState.ATTENTION -> 1; CheckState.UNKNOWN -> 2; CheckState.OK -> 3 } }
    }
}
