package com.bright.onepluscare.data

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.provider.Settings
import com.bright.onepluscare.core.hardening.HardeningFacts
import com.bright.onepluscare.core.hardening.WifiSecurity
import com.bright.onepluscare.core.network.PrivateDnsMode
import com.bright.onepluscare.core.rules.RecommendationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the device-hardening facts from public APIs only. Anything unreadable stays null ("check yourself"). */
class HardeningRepository(private val context: Context) {
    suspend fun facts(shizukuRunning: Boolean): HardeningFacts = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val patch = Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() }
        HardeningFacts(
            screenLockSecure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure,
            patchAgeDays = RecommendationEngine.patchAgeDays(patch, System.currentTimeMillis()),
            developerOptions = runCatching { Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrNull(),
            usbDebugging = runCatching { Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrNull(),
            storageEncrypted = runCatching {
                when (context.getSystemService(DevicePolicyManager::class.java)?.storageEncryptionStatus) {
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE, DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER,
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> true
                    DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> false
                    else -> null
                }
            }.getOrNull(),
            lockScreenShowsContent = lockScreenContent(),
            installerApps = installerApps(),
            wifi = wifiSecurity(),
            privateDns = privateDns(),
            shizukuRunning = shizukuRunning,
        )
    }

    /** Secure settings these keys live in are readable on most builds; when not, the check says "unknown". */
    private fun lockScreenContent(): Boolean? = runCatching {
        val cr = context.contentResolver
        val show = Settings.Secure.getInt(cr, "lock_screen_show_notifications")
        if (show == 0) false else Settings.Secure.getInt(cr, "lock_screen_allow_private_notifications") == 1
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installerApps(): List<String> {
        val pm = context.packageManager
        return runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrDefault(emptyList())
            .filter { p ->
                val ai = p.applicationInfo ?: return@filter false
                ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 && p.packageName != context.packageName &&
                    p.requestedPermissions?.contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES) == true
            }
            .map { p -> runCatching { p.applicationInfo!!.loadLabel(pm).toString() }.getOrDefault(p.packageName) }
            .sorted()
    }

    private fun wifiSecurity(): WifiSecurity {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return WifiSecurity.UNKNOWN
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return WifiSecurity.NOT_ON_WIFI
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return WifiSecurity.NOT_ON_WIFI
        if (Build.VERSION.SDK_INT < 31) return WifiSecurity.UNKNOWN
        val info = caps.transportInfo as? WifiInfo ?: return WifiSecurity.UNKNOWN
        return when (info.currentSecurityType) {
            WifiInfo.SECURITY_TYPE_OPEN -> WifiSecurity.OPEN
            WifiInfo.SECURITY_TYPE_WEP -> WifiSecurity.WEP
            WifiInfo.SECURITY_TYPE_OWE -> WifiSecurity.ENHANCED_OPEN
            WifiInfo.SECURITY_TYPE_UNKNOWN -> WifiSecurity.UNKNOWN
            else -> WifiSecurity.SECURED
        }
    }

    private fun privateDns(): PrivateDnsMode {
        if (Build.VERSION.SDK_INT < 28) return PrivateDnsMode.UNKNOWN
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return PrivateDnsMode.UNKNOWN
        val props = com.bright.onepluscare.firewall.AppNetwork.forOwnTraffic(context)?.let { cm.getLinkProperties(it) } ?: return PrivateDnsMode.UNKNOWN
        return when {
            !props.isPrivateDnsActive -> PrivateDnsMode.OFF
            props.privateDnsServerName != null -> PrivateDnsMode.STRICT
            else -> PrivateDnsMode.OPPORTUNISTIC
        }
    }
}
