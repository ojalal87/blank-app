package com.bright.onepluscare.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bright.onepluscare.MainActivity
import com.bright.onepluscare.R
import com.bright.onepluscare.core.health.HealthState

object Notifications {
    const val CHANNEL_SCAN = "maintenance_scan"
    private const val ID_SCAN = 3003

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_SCAN, context.getString(R.string.notif_channel_scan), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notif_channel_scan_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Lock-screen privacy: the public version only says a scan finished; numbers appear after unlock.
     * The PendingIntent is explicit and immutable.
     */
    fun showScanResult(context: Context, state: HealthState, reclaimableBytes: Long, items: Int) {
        if (!canPost(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = context.getString(
            when (state) {
                HealthState.CRITICAL -> R.string.notif_title_critical
                HealthState.ATTENTION -> R.string.notif_title_attention
                HealthState.HEALTHY -> R.string.notif_title_done
            },
        )
        val text = if (reclaimableBytes > 0) {
            context.getString(R.string.notif_text_reclaimable, items, Formatter.formatShortFileSize(context, reclaimableBytes))
        } else {
            context.getString(R.string.notif_text_nothing)
        }
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_stat_care)
            .setContentTitle(context.getString(R.string.notif_title_done))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_stat_care)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        // Explicit check in this method so lint can verify the POST_NOTIFICATIONS guard.
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(context).notify(ID_SCAN, n)
        } catch (_: SecurityException) {
        }
    }
}
