package com.bright.onepluscare.core.apps

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One installed app as seen by App Doctor. Every metric is nullable: null means "not available"
 * (no Usage Access, OEM refused StorageStats, or the app has no usage record) and must be shown as such.
 */
data class AppRecord(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val enabled: Boolean,
    val installedMillis: Long?,
    val updatedMillis: Long?,
    /** StorageStats.getAppBytes(): APK + code. */
    val packageBytes: Long?,
    /** StorageStats.getDataBytes(): app data INCLUDING its cache. */
    val dataBytes: Long?,
    /** StorageStats.getCacheBytes(): subset of dataBytes. */
    val cacheBytes: Long?,
    val foregroundMillis: Long?,
    val lastUsedMillis: Long?,
    val sensitivePermissions: Set<SensitivePermission>,
    val ignoresBatteryOptimizations: Boolean?,
) {
    val totalBytes: Long? get() = if (packageBytes != null && dataBytes != null) packageBytes + dataBytes else null
}

enum class SensitivePermission { CAMERA, MICROPHONE, LOCATION, BACKGROUND_LOCATION, CONTACTS, CALL_LOG, SMS, PHONE, BODY_SENSORS, NEARBY_DEVICES }

enum class AppFlag { UNUSED_LONG, LARGE_CACHE, HEAVY_USE, SENSITIVE_AND_UNUSED, BATTERY_EXEMPT }

enum class AppSort { NAME, TOTAL_SIZE, CACHE, LAST_USED, FOREGROUND }

data class AppQuery(
    val text: String = "",
    val includeSystem: Boolean = false,
    val sort: AppSort = AppSort.TOTAL_SIZE,
    val onlyFlagged: Boolean = false,
)

object AppEvaluator {
    const val UNUSED_DAYS = 60L
    const val LARGE_CACHE_BYTES = 500_000_000L
    val HEAVY_USE_MS: Long = TimeUnit.HOURS.toMillis(20)

    fun flags(app: AppRecord, nowMillis: Long, usageWindowDays: Int): Set<AppFlag> {
        val out = LinkedHashSet<AppFlag>()
        val daysSinceUse = daysSince(app.lastUsedMillis, nowMillis)
        // "Unused" only when Usage Access gave us a record AND the app was installed before the window began.
        val installedLongAgo = app.installedMillis?.let { TimeUnit.MILLISECONDS.toDays(nowMillis - it) >= UNUSED_DAYS } ?: false
        val unused = !app.isSystem && installedLongAgo &&
            (daysSinceUse?.let { it >= UNUSED_DAYS } ?: (app.foregroundMillis == 0L && usageWindowDays >= UNUSED_DAYS))
        if (unused) out += AppFlag.UNUSED_LONG
        if ((app.cacheBytes ?: 0L) >= LARGE_CACHE_BYTES) out += AppFlag.LARGE_CACHE
        if ((app.foregroundMillis ?: 0L) >= HEAVY_USE_MS) out += AppFlag.HEAVY_USE
        if (unused && app.sensitivePermissions.isNotEmpty()) out += AppFlag.SENSITIVE_AND_UNUSED
        if (!app.isSystem && app.ignoresBatteryOptimizations == true) out += AppFlag.BATTERY_EXEMPT
        return out
    }

    fun daysSince(millis: Long?, now: Long): Long? =
        millis?.takeIf { it > 0L }?.let { TimeUnit.MILLISECONDS.toDays((now - it).coerceAtLeast(0L)) }

    fun query(apps: List<AppRecord>, q: AppQuery, nowMillis: Long, usageWindowDays: Int): List<AppRecord> {
        val needle = q.text.trim().lowercase(Locale.ROOT)
        val filtered = apps.filter { a ->
            (q.includeSystem || !a.isSystem) &&
                (needle.isEmpty() || a.label.lowercase(Locale.ROOT).contains(needle) || a.packageName.lowercase(Locale.ROOT).contains(needle)) &&
                (!q.onlyFlagged || flags(a, nowMillis, usageWindowDays).isNotEmpty())
        }
        // Unknown values always sort last, regardless of direction.
        val cmp: Comparator<AppRecord> = when (q.sort) {
            AppSort.NAME -> compareBy { it.label.lowercase(Locale.ROOT) }
            AppSort.TOTAL_SIZE -> compareBy<AppRecord> { it.totalBytes == null }.thenByDescending { it.totalBytes ?: 0L }
            AppSort.CACHE -> compareBy<AppRecord> { it.cacheBytes == null }.thenByDescending { it.cacheBytes ?: 0L }
            AppSort.LAST_USED -> compareBy<AppRecord> { it.lastUsedMillis == null }.thenBy { it.lastUsedMillis ?: Long.MAX_VALUE }
            AppSort.FOREGROUND -> compareBy<AppRecord> { it.foregroundMillis == null }.thenByDescending { it.foregroundMillis ?: 0L }
        }
        return filtered.sortedWith(cmp.thenBy { it.packageName })
    }
}
