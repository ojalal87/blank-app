package com.bright.onepluscare.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import com.bright.onepluscare.core.usage.UidUsage
import com.bright.onepluscare.core.usage.UnusualUsage
import com.bright.onepluscare.core.usage.UsageAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class UsagePeriod(val days: Int) { TODAY(1), WEEK(7), MONTH(30) }

/** A UID as the user should see it: an app label, or a named system bucket. */
data class UsageOwner(val uid: Int, val label: String, val packages: List<String>, val isSystem: Boolean, val special: SpecialUid?)

enum class SpecialUid { REMOVED_APPS, TETHERING, SYSTEM }

data class UsageRow(val owner: UsageOwner, val usage: UidUsage)

data class DataUsageReport(
    val period: UsagePeriod,
    val rows: List<UsageRow>,
    val totalWifi: Long,
    val totalMobile: Long,
    val unusual: List<Pair<UsageOwner, UnusualUsage>>,
    /** Mobile figures could not be read (some OEM builds refuse the all-SIM query). */
    val mobileUnavailable: Boolean,
    val measuredAtMillis: Long,
)

/**
 * Per-app data usage from Android's own NetworkStatsManager (needs "Usage access"). Android aggregates in
 * buckets of about two hours, so the newest hours can lag. Nothing is stored: figures are read on demand.
 */
class DataUsageRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    suspend fun load(period: UsagePeriod, now: Long = System.currentTimeMillis()): DataUsageReport? = withContext(Dispatchers.IO) {
        val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return@withContext null
        val start = startOfDay(now, period.days - 1)
        val wifi = summary(nsm, ConnectivityManager.TYPE_WIFI, start, now) ?: return@withContext null
        val mobile = summary(nsm, ConnectivityManager.TYPE_MOBILE, start, now)
        val merged = HashMap<Int, UidUsage>()
        wifi.forEach { (uid, u) -> merged[uid] = (merged[uid] ?: UidUsage(uid)) + u }
        mobile?.forEach { (uid, u) -> merged[uid] = (merged[uid] ?: UidUsage(uid)) + u }
        val owners = HashMap<Int, UsageOwner>()
        fun owner(uid: Int) = owners.getOrPut(uid) { resolve(uid) }
        val rows = merged.values.filter { it.total > 0 }.map { UsageRow(owner(it.uid), it) }.sortedByDescending { it.usage.total }
        val unusual = unusualToday(nsm, now).map { owner(it.uid) to it }
        DataUsageReport(period, rows, rows.sumOf { it.usage.wifi }, rows.sumOf { it.usage.mobile }, unusual, mobile == null, now)
    }

    /** For the background watch: today's unusual background uploaders with resolved owners. */
    suspend fun unusualNow(now: Long = System.currentTimeMillis()): List<Pair<UsageOwner, UnusualUsage>>? = withContext(Dispatchers.IO) {
        val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return@withContext null
        unusualToday(nsm, now).map { resolve(it.uid) to it }
    }

    /** Today's background upload per UID judged against the previous 14 days of the same app. */
    private suspend fun unusualToday(nsm: NetworkStatsManager, now: Long): List<UnusualUsage> {
        val todayStart = startOfDay(now, 0)
        val today = backgroundTx(nsm, todayStart, now) ?: return emptyList()
        val candidates = today.filterValues { it >= UsageAnalyzer.MIN_BYTES }
        if (candidates.isEmpty()) return emptyList()
        val days = (1..14).map { d ->
            currentCoroutineContext().ensureActive()
            val s = startOfDay(now, d)
            backgroundTx(nsm, s, startOfDay(now, d - 1))
        }
        return candidates.mapNotNull { (uid, bytes) ->
            UsageAnalyzer.judge(uid, bytes, days.reversed().map { day -> day?.let { it[uid] ?: 0L } })
        }.sortedByDescending { it.todayBytes }
    }

    private fun backgroundTx(nsm: NetworkStatsManager, start: Long, end: Long): Map<Int, Long>? {
        val out = HashMap<Int, Long>()
        var any = false
        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            val s = summary(nsm, type, start, end) ?: continue
            any = true
            s.forEach { (uid, u) -> out[uid] = (out[uid] ?: 0L) + u.backgroundTx }
        }
        return if (any) out else null
    }

    @Suppress("DEPRECATION")
    private fun summary(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): Map<Int, UidUsage>? {
        val stats = try {
            nsm.querySummary(type, null, start, end)
        } catch (_: SecurityException) {
            return null
        } catch (_: Exception) {
            return null
        } ?: return null
        val out = HashMap<Int, UidUsage>()
        val b = NetworkStats.Bucket()
        stats.use {
            while (it.hasNextBucket()) {
                it.getNextBucket(b)
                val bg = b.state == NetworkStats.Bucket.STATE_DEFAULT
                val u = if (type == ConnectivityManager.TYPE_WIFI) UidUsage(b.uid, wifiRx = b.rxBytes, wifiTx = b.txBytes)
                else UidUsage(b.uid, mobileRx = b.rxBytes, mobileTx = b.txBytes)
                val withBg = if (bg) u.copy(backgroundRx = b.rxBytes, backgroundTx = b.txBytes) else u
                out[b.uid] = (out[b.uid] ?: UidUsage(b.uid)) + withBg
            }
        }
        return out
    }

    private fun resolve(uid: Int): UsageOwner {
        val special = when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> SpecialUid.REMOVED_APPS
            NetworkStats.Bucket.UID_TETHERING -> SpecialUid.TETHERING
            Process.SYSTEM_UID, 0 -> SpecialUid.SYSTEM
            else -> null
        }
        if (special != null) return UsageOwner(uid, "", emptyList(), true, special)
        val pkgs = runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty().sorted()
        val infos = pkgs.mapNotNull { p -> runCatching { pm.getApplicationInfo(p, 0) }.getOrNull() }
        val label = infos.firstOrNull()?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() } ?: pkgs.firstOrNull() ?: "UID $uid"
        val system = infos.isNotEmpty() && infos.all { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0 }
        return UsageOwner(uid, if (pkgs.size > 1) "$label (+${pkgs.size - 1})" else label, pkgs, system || pkgs.isEmpty(), null)
    }

    private fun startOfDay(now: Long, daysAgo: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
