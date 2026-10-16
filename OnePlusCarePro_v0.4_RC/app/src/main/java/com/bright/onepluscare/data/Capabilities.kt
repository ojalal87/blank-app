package com.bright.onepluscare.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.bright.onepluscare.core.oem.OemDetector
import com.bright.onepluscare.core.oem.OemProfile
import com.bright.onepluscare.core.storage.StorageAccessMode

/** First-run and on-resume capability detection, shown in the Permission Center. */
data class Capabilities(
    val sdkInt: Int,
    val storageMode: StorageAccessMode,
    val safeTrashAvailable: Boolean,
    val usageAccess: Boolean,
    val notificationsAllowed: Boolean,
    val appIgnoresBatteryOptimizations: Boolean,
    val oem: OemProfile,
) {
    fun asReportMap(): Map<String, Boolean> = mapOf(
        "storageFilesystem" to (storageMode == StorageAccessMode.FILESYSTEM),
        "storageMedia" to (storageMode == StorageAccessMode.MEDIASTORE || storageMode == StorageAccessMode.MEDIASTORE_PARTIAL),
        "usageAccess" to usageAccess,
        "notifications" to notificationsAllowed,
        "batteryOptimizationExempt" to appIgnoresBatteryOptimizations,
    )

    companion object {
        fun detect(context: Context, storage: StorageRepository, apps: AppsRepository): Capabilities {
            val notifications = (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            val power = context.getSystemService(PowerManager::class.java)
            return Capabilities(
                sdkInt = Build.VERSION.SDK_INT,
                storageMode = storage.accessMode(),
                safeTrashAvailable = storage.trashStore != null,
                usageAccess = apps.hasUsageAccess(),
                notificationsAllowed = notifications,
                appIgnoresBatteryOptimizations = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
                oem = OemDetector.detect(Build.MANUFACTURER, Build.BRAND),
            )
        }
    }
}
