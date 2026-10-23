package com.bright.onepluscare.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.bright.onepluscare.core.oem.GuidanceItem

/**
 * Every system screen the app opens. Only public android.provider.Settings / Intent actions are used;
 * there are no OEM-private activities, component names or reflection. Each launch falls back to the
 * main Settings screen if the device does not implement the action, and reports whether it worked.
 */
class SystemIntents(private val context: Context) {

    enum class Target {
        APP_ALL_FILES_ACCESS, USAGE_ACCESS, APP_NOTIFICATIONS, BATTERY_USAGE, BATTERY_OPTIMIZATION_LIST,
        BATTERY_SAVER, STORAGE, INTERNET_PANEL, WIFI, PRIVATE_DNS_NETWORK, PRIVACY, DEVELOPER, SECURITY,
        DEVICE_INFO, SETTINGS, SHIZUKU_APP, APP_LANGUAGE, SHIZUKU_STORE, ACCESSIBILITY, NOTIFICATION_LISTENERS, VPN, UNKNOWN_SOURCES,
    }

    fun intentFor(target: Target, packageName: String = context.packageName): Intent? = when (target) {
        Target.APP_ALL_FILES_ACCESS ->
            if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")) else null
        Target.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        Target.APP_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        Target.BATTERY_USAGE -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        Target.BATTERY_OPTIMIZATION_LIST -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        Target.BATTERY_SAVER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        Target.STORAGE -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        Target.INTERNET_PANEL -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
        Target.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        // Private DNS lives under Network & internet on AOSP and OxygenOS; there is no public direct action.
        Target.PRIVATE_DNS_NETWORK -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        Target.PRIVACY -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
        Target.DEVELOPER -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        Target.SECURITY -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        Target.DEVICE_INFO -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        Target.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        Target.SHIZUKU_APP -> context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        Target.SHIZUKU_STORE -> Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"))
        Target.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Target.NOTIFICATION_LISTENERS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Target.VPN -> Intent(Settings.ACTION_VPN_SETTINGS)
        Target.UNKNOWN_SOURCES -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        Target.APP_LANGUAGE ->
            if (Build.VERSION.SDK_INT >= 33) Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}")) else Intent(Settings.ACTION_LOCALE_SETTINGS)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(target: Target): Boolean = start(intentFor(target)) || (target != Target.SHIZUKU_APP && target != Target.SHIZUKU_STORE && start(intentFor(Target.SETTINGS)))

    /** Per-app details. Only validated package names (from PackageManager) reach this. */
    fun openAppDetails(packageName: String): Boolean {
        if (!PACKAGE_NAME.matches(packageName)) return false
        return start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openGuidance(item: GuidanceItem): Boolean = when (item) {
        GuidanceItem.REVIEW_BATTERY_OPTIMIZATION_EXEMPTIONS -> open(Target.BATTERY_OPTIMIZATION_LIST)
        GuidanceItem.REVIEW_BATTERY_USAGE -> open(Target.BATTERY_USAGE)
        GuidanceItem.PER_APP_BACKGROUND_ACTIVITY -> openAppDetails(context.packageName)
        GuidanceItem.OPTIMIZED_CHARGING_INFO -> open(Target.BATTERY_USAGE)
        GuidanceItem.SCHEDULED_SCAN_RELIABILITY -> openAppDetails(context.packageName)
        GuidanceItem.STORAGE_SETTINGS -> open(Target.STORAGE)
    }

    private fun start(intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
