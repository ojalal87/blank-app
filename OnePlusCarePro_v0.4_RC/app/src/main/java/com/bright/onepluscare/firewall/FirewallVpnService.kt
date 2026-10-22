package com.bright.onepluscare.firewall

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.bright.onepluscare.R
import com.bright.onepluscare.core.security.FirewallRules
import java.io.FileInputStream

/**
 * No-root firewall. A local VPN that routes ONLY the apps the user blocked into a tunnel whose packets are
 * read and discarded, so those apps have no network. Every other app bypasses the VPN entirely (it is never
 * added with addAllowedApplication), so their traffic is untouched and never inspected. Nothing is logged,
 * forwarded or sent anywhere. Starts only from the Firewall screen after the system VPN consent; there is no
 * boot receiver (the user can make it persistent with Android's own "Always-on VPN" setting).
 */
class FirewallVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var drain: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        // Always-on VPN starts the service with a null intent / SERVICE_INTERFACE action.
        return if (establish()) START_STICKY else {
            shutdown()
            START_NOT_STICKY
        }
    }

    private fun establish(): Boolean = synchronized(this) {
        val blocked = FirewallRules.sanitize(FirewallStore(this).current(), packageName)
        if (!FirewallRules.canStart(blocked)) return false
        val builder = Builder()
            .setSession(getString(R.string.fw_session_name))
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            .addRoute("::", 0)
            .setBlocking(true)
        var added = 0
        for (pkg in blocked) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (_: PackageManager.NameNotFoundException) {
                // Uninstalled since it was blocked; skip.
            }
        }
        // VpnService treats "no allowed applications" as "every app": never establish in that state.
        if (added == 0) return false
        val fd = try {
            builder.establish()
        } catch (_: Exception) {
            null
        } ?: return false
        closeTunnel()
        tun = fd
        drain = Thread({
            val buf = ByteArray(32 * 1024)
            try {
                FileInputStream(fd.fileDescriptor).use { input -> while (!Thread.currentThread().isInterrupted && input.read(buf) >= 0) Unit }
            } catch (_: Exception) {
            }
        }, "firewall-drain").apply { isDaemon = true; start() }
        FirewallStore.running.value = true
        true
    }

    private fun closeTunnel() {
        drain?.interrupt()
        drain = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun shutdown() {
        synchronized(this) { closeTunnel() }
        FirewallStore.running.value = false
        stopSelf()
    }

    override fun onRevoke() {
        // The user turned the VPN off in system Settings, or another VPN took over.
        shutdown()
    }

    override fun onDestroy() {
        synchronized(this) { closeTunnel() }
        FirewallStore.running.value = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.bright.onepluscare.firewall.START"
        private const val ACTION_STOP = "com.bright.onepluscare.firewall.STOP"

        /** Returns the consent intent if the user has not yet approved this app as a VPN, else null. */
        fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

        fun start(context: Context) {
            context.startService(Intent(context, FirewallVpnService::class.java).setAction(ACTION_START))
        }

        /** Re-applies the blocked list when the user changes it while the firewall is on. */
        fun reload(context: Context) = start(context)

        fun stop(context: Context) {
            context.startService(Intent(context, FirewallVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
