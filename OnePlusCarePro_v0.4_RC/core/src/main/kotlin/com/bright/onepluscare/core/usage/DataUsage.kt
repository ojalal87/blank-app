package com.bright.onepluscare.core.usage

import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.max

/** Bytes for one UID over one period, split by network and by foreground/background. */
data class UidUsage(
    val uid: Int,
    val wifiRx: Long = 0,
    val wifiTx: Long = 0,
    val mobileRx: Long = 0,
    val mobileTx: Long = 0,
    /** Bytes sent while the app was not in the foreground (both networks). */
    val backgroundTx: Long = 0,
    val backgroundRx: Long = 0,
) {
    val wifi: Long get() = wifiRx + wifiTx
    val mobile: Long get() = mobileRx + mobileTx
    val total: Long get() = wifi + mobile
    val background: Long get() = backgroundRx + backgroundTx

    operator fun plus(o: UidUsage) = UidUsage(
        uid, wifiRx + o.wifiRx, wifiTx + o.wifiTx, mobileRx + o.mobileRx, mobileTx + o.mobileTx,
        backgroundTx + o.backgroundTx, backgroundRx + o.backgroundRx,
    )
}

enum class UnusualReason { BACKGROUND_UPLOAD_SPIKE, NEW_BACKGROUND_UPLOADER }

data class UnusualUsage(val uid: Int, val reason: UnusualReason, val todayBytes: Long, val typicalBytes: Long?)

/**
 * Flags background uploads that are far above an app's own recent days. A robust baseline (median and MAD
 * of log bytes, past days only, the day being judged excluded) so one earlier spike cannot hide the next.
 * This says "unusual for this app", never "malware": backups and photo sync look the same.
 */
object UsageAnalyzer {
    const val MIN_HISTORY_DAYS = 7
    /** Below this, nothing is worth an alert whatever the ratio. */
    const val MIN_BYTES = 20L * 1024 * 1024
    private const val Z_THRESHOLD = 6.0
    private const val SCALE_FLOOR = 0.5

    fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    /**
     * [history]: background upload bytes per previous day, oldest first; null = day not measured (never
     * treated as zero). Returns null when nothing is unusual or there is not enough history to judge.
     */
    fun judge(uid: Int, today: Long, history: List<Long?>): UnusualUsage? {
        if (today < MIN_BYTES) return null
        val known = history.filterNotNull()
        if (known.size < MIN_HISTORY_DAYS) return null
        val logs = known.map { ln1p(it.toDouble()) }
        val center = median(logs)
        val mad = median(logs.map { abs(it - center) })
        val z = (ln1p(today.toDouble()) - center) / max(1.4826 * mad, SCALE_FLOOR)
        val typical = kotlin.math.expm1(center).toLong()
        return when {
            known.all { it < 1024 * 1024 } -> UnusualUsage(uid, UnusualReason.NEW_BACKGROUND_UPLOADER, today, typical)
            z >= Z_THRESHOLD -> UnusualUsage(uid, UnusualReason.BACKGROUND_UPLOAD_SPIKE, today, typical)
            else -> null
        }
    }
}
