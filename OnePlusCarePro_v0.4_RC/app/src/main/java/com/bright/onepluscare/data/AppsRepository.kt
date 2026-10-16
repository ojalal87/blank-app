package com.bright.onepluscare.data

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.storage.StorageManager
import com.bright.onepluscare.core.apps.AppRecord
import com.bright.onepluscare.core.apps.SensitivePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class AppInventory(val apps: List<AppRecord>, val usageAccess: Boolean, val windowDays: Int, val loadedAtMillis: Long)

/**
 * App Doctor data source. Package visibility is limited to launchable apps (manifest <queries>), so
 * background-only packages are intentionally not listed. Usage and storage metrics require the user-granted
 * "Usage access" special permission; without it they are null ("not available"), never zero.
 */
class AppsRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
        } catch (_: Exception) {
            AppOpsManager.MODE_ERRORED
        }
        // MODE_DEFAULT means "fall back to the permission", which PACKAGE_USAGE_STATS never grants by itself here.
        return mode == AppOpsManager.MODE_ALLOWED ||
            (mode == AppOpsManager.MODE_DEFAULT && context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED)
    }

    suspend fun load(windowDays: Int = DEFAULT_WINDOW_DAYS, onProgress: (Int, Int) -> Unit = { _, _ -> }): AppInventory = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val usageAccess = hasUsageAccess()
        val usage = if (usageAccess) usageStats(now - TimeUnit.DAYS.toMillis(windowDays.toLong()), now) else emptyMap()
        val storage = context.getSystemService(StorageStatsManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val packages = launchablePackages()
        val out = ArrayList<AppRecord>(packages.size)
        packages.forEachIndexed { index, pkg ->
            currentCoroutineContext().ensureActive()
            if (index % 10 == 0) onProgress(index, packages.size)
            val info = packageInfo(pkg) ?: return@forEachIndexed
            val app = info.applicationInfo ?: return@forEachIndexed
            var codeBytes: Long? = null
            var dataBytes: Long? = null
            var cacheBytes: Long? = null
            if (usageAccess && storage != null) {
                try {
                    // Per-package, not per-UID: v0.3 used queryStatsForUid, which merges apps sharing a UID.
                    val s = storage.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                    codeBytes = s.appBytes
                    dataBytes = s.dataBytes
                    cacheBytes = s.cacheBytes
                } catch (_: Exception) {
                    // OEM / work-profile refusal: leave as "not available".
                }
            }
            val u = usage[pkg]
            out += AppRecord(
                packageName = pkg,
                label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg),
                isSystem = (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                enabled = app.enabled,
                installedMillis = info.firstInstallTime.takeIf { it > 0 },
                updatedMillis = info.lastUpdateTime.takeIf { it > 0 },
                packageBytes = codeBytes,
                dataBytes = dataBytes,
                cacheBytes = cacheBytes,
                foregroundMillis = if (usageAccess) (u?.first ?: 0L) else null,
                lastUsedMillis = if (usageAccess) u?.second?.takeIf { it > 0 } else null,
                sensitivePermissions = sensitive(info),
                ignoresBatteryOptimizations = runCatching { power?.isIgnoringBatteryOptimizations(pkg) }.getOrNull(),
            )
        }
        onProgress(packages.size, packages.size)
        AppInventory(out, usageAccess, windowDays, now)
    }

    /** Pair(foregroundMillis, lastTimeUsed) per package over the window. Bucketed by the system: approximate. */
    private fun usageStats(start: Long, end: Long): Map<String, Pair<Long, Long>> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        return try {
            usm.queryAndAggregateUsageStats(start, end).mapValues { (_, s) -> s.totalTimeInForeground to s.lastTimeUsed }
        } catch (_: SecurityException) {
            emptyMap()
        }
    }

    private fun launchablePackages(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return list.mapNotNull { it.activityInfo?.packageName }.distinct().filter { it != context.packageName }
    }

    private fun packageInfo(pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun sensitive(info: PackageInfo): Set<SensitivePermission> {
        val names = info.requestedPermissions ?: return emptySet()
        val flags = info.requestedPermissionsFlags ?: return emptySet()
        val out = LinkedHashSet<SensitivePermission>()
        for (i in names.indices) {
            if (i >= flags.size || (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0) continue
            PERMISSION_MAP[names[i]]?.let(out::add)
        }
        return out
    }

    companion object {
        const val DEFAULT_WINDOW_DAYS = 30

        /** Runtime ("dangerous") permissions only, where the granted flag is meaningful. */
        private val PERMISSION_MAP: Map<String, SensitivePermission> = mapOf(
            Manifest.permission.CAMERA to SensitivePermission.CAMERA,
            Manifest.permission.RECORD_AUDIO to SensitivePermission.MICROPHONE,
            Manifest.permission.ACCESS_FINE_LOCATION to SensitivePermission.LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION to SensitivePermission.LOCATION,
            "android.permission.ACCESS_BACKGROUND_LOCATION" to SensitivePermission.BACKGROUND_LOCATION,
            Manifest.permission.READ_CONTACTS to SensitivePermission.CONTACTS,
            Manifest.permission.READ_CALL_LOG to SensitivePermission.CALL_LOG,
            Manifest.permission.READ_SMS to SensitivePermission.SMS,
            Manifest.permission.RECEIVE_SMS to SensitivePermission.SMS,
            Manifest.permission.READ_PHONE_STATE to SensitivePermission.PHONE,
            Manifest.permission.BODY_SENSORS to SensitivePermission.BODY_SENSORS,
            "android.permission.BLUETOOTH_SCAN" to SensitivePermission.NEARBY_DEVICES,
            "android.permission.NEARBY_WIFI_DEVICES" to SensitivePermission.NEARBY_DEVICES,
        )
    }
}
