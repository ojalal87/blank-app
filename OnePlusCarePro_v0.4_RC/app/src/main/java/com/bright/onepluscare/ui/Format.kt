package com.bright.onepluscare.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bright.onepluscare.R
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Locale-aware formatting. Byte sizes use the same SI units as Android Settings (1 GB = 10^9 bytes). */
object Fmt {
    fun bytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))

    fun signedBytes(context: Context, bytes: Long): String = (if (bytes < 0) "−" else "+") + bytes(context, kotlin.math.abs(bytes))

    fun percent(value: Double, digits: Int = 0): String {
        val nf = NumberFormat.getPercentInstance(Locale.getDefault())
        nf.maximumFractionDigits = digits
        nf.minimumFractionDigits = digits
        return nf.format(value / 100.0)
    }

    fun number(value: Number, digits: Int = 0): String {
        val nf = NumberFormat.getNumberInstance(Locale.getDefault())
        nf.maximumFractionDigits = digits
        nf.minimumFractionDigits = digits
        return nf.format(value)
    }

    fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String =
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

    fun dateTime(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

    fun date(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR)
}

@Composable
fun bytesText(bytes: Long): String = Fmt.bytes(LocalContext.current, bytes)

@Composable
fun celsiusText(c: Float): String = stringResource(R.string.unit_celsius, Fmt.number(c, 1))

@Composable
fun durationText(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return if (hours > 0) stringResource(R.string.unit_hours_minutes, Fmt.number(hours), Fmt.number(minutes))
    else stringResource(R.string.unit_minutes, Fmt.number(minutes))
}

@Composable
fun daysText(days: Long): String = stringResource(R.string.unit_days, Fmt.number(days))
