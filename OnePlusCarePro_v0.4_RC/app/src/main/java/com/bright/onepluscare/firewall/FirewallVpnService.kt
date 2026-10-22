package com.bright.onepluscare.firewall

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.bright.onepluscare.R
import com.bright.onepluscare.core.security.FirewallPlanner
import com.bright.onepluscare.core.security.NetKind
import com.bright.onepluscare.core.security.TunnelPlan
import java.io.FileInputStream

/**
 * No-root firewall. A local VPN that routes ONLY the apps blocked on the current network (Wi-Fi or mobile)
 * into a tunnel whose packets are read and discarded, so those apps have no network there. Every other app
 * bypasses the VPN entirely, so its traffic is untouched and never inspected. On a network switch the tunnel
 * is rebuilt for the new network. Nothing is logged, forwarded or sent anywhere. Starts only from the
 * Firewall screen after the system VPN consent; there is no boot receiver (Android's "Always-on VPN"
 * setting is the user-controlled way to keep it on).
 */
class FirewallVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var drain: Thread? = null
    private var plan: TunnelPlan? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    /** Underlying (non-VPN) networks with internet, and their transports. */
    private val networks = LinkedHashMap<Network, NetworkCapabilities>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (FirewallStore(this).current().isEmpty) {
            shutdown()
            return START_NOT_STICKY
        }
        watchNetworks()
        // Always-on VPN starts the service with a null intent / SERVICE_INTERFACE action.
        reconfigure(force = intent?.action == ACTION_RELOAD)
        return START_STICKY
    }

    private fun watchNetworks() {
        if (callback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                synchronized(networks) { networks[network] = caps }
                reconfigure(force = false)
            }

            override fun onLost(network: Network) {
                synchronized(networks) { networks.remove(network) }
                reconfigure(force = false)
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb) }.onSuccess { callback = cb }
    }

    /** Android prefers Wi-Fi over cellular when both are up; anything else (Ethernet, USB) is OTHER. */
    private fun currentNetwork(): Pair<NetKind, Network?> = synchronized(networks) {
        val wifi = networks.entries.firstOrNull { it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        val other = networks.entries.firstOrNull { it.value.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || it.value.hasTransport(NetworkCapabilities.TRANSPORT_USB) }
        val cell = networks.entries.firstOrNull { it.value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
        when {
            other != null -> NetKind.OTHER to other.key
            wifi != null -> NetKind.WIFI to wifi.key
            cell != null -> NetKind.MOBILE to cell.key
            networks.isNotEmpty() -> NetKind.OTHER to networks.keys.first()
            else -> NetKind.NONE to null
        }
    }

    private fun reconfigure(force: Boolean) = synchronized(this) {
        val policy = FirewallStore(this).current()
        if (policy.isEmpty) {
            shutdown()
            return
        }
        val (kind, underlying) = currentNetwork()
        // No network yet (or between networks): keep whatever is installed; start self-only if nothing is.
        val next = FirewallPlanner.plan(policy, kind, packageName)
            ?: plan ?: TunnelPlan(setOf(packageName), selfOnly = true)
        if (!force && next == plan && tun != null) {
            underlying?.let { runCatching { setUnderlyingNetworks(arrayOf(it)) } }
            publish(kind)
            return
        }
        if (!establish(next, underlying)) {
            shutdown()
            return
        }
        plan = next
        publish(kind)
    }

    private fun establish(p: TunnelPlan, underlying: Network?): Boolean {
        val builder = Builder()
            .setSession(getString(R.string.fw_session_name))
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            .addRoute("::", 0)
            .setBlocking(true)
        underlying?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        var added = 0
        for (pkg in p.apps) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (_: PackageManager.NameNotFoundException) {
                // Uninstalled since it was blocked; skip.
            }
        }
        if (added == 0) {
            // VpnService treats "no allowed applications" as "every app": never establish in that state.
            builder.addAllowedApplication(packageName)
        }
        // Only the self-only tunnel allows bypass, so this app can still reach the real network explicitly.
        // A tunnel holding blocked apps never allows bypass.
        if (p.selfOnly || added == 0) builder.allowBypass()
        val fd = try {
            builder.establish()
        } catch (_: Exception) {
            null
        } ?: return false
        val old = tun
        val oldDrain = drain
        tun = fd
        drain = Thread({
            val buf = ByteArray(32 * 1024)
            try {
                FileInputStream(fd.fileDescriptor).use { input -> while (!Thread.currentThread().isInterrupted && input.read(buf) >= 0) Unit }
            } catch (_: Exception) {
            }
        }, "firewall-drain").apply { isDaemon = true; start() }
        // Close the previous tunnel only after the new one is up, so blocked apps never leak in between.
        oldDrain?.interrupt()
        runCatching { old?.close() }
        return true
    }

    private fun publish(kind: NetKind) {
        val p = plan
        FirewallStore.status.value = FirewallStatus(running = tun != null, network = kind, blockingNow = if (p == null || p.selfOnly) 0 else p.apps.size, selfOnly = p?.selfOnly == true)
    }

    private fun closeTunnel() {
        drain?.interrupt()
        drain = null
        runCatching { tun?.close() }
        tun = null
        plan = null
    }

    private fun shutdown() {
        synchronized(this) { closeTunnel() }
        unwatch()
        FirewallStore.status.value = FirewallStatus()
        stopSelf()
    }

    private fun unwatch() {
        val cb = callback ?: return
        callback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        synchronized(networks) { networks.clear() }
    }

    override fun onRevoke() {
        // The user turned the VPN off in system Settings, or another VPN took over.
        shutdown()
    }

    override fun onDestroy() {
        synchronized(this) { closeTunnel() }
        unwatch()
        FirewallStore.status.value = FirewallStatus()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.bright.onepluscare.firewall.START"
        private const val ACTION_RELOAD = "com.bright.onepluscare.firewall.RELOAD"
        private const val ACTION_STOP = "com.bright.onepluscare.firewall.STOP"

        /** Returns the consent intent if the user has not yet approved this app as a VPN, else null. */
        fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

        fun start(context: Context) {
            context.startService(Intent(context, FirewallVpnService::class.java).setAction(ACTION_START))
        }

        /** Re-applies the lists when the user changes them while the firewall is on. */
        fun reload(context: Context) {
            context.startService(Intent(context, FirewallVpnService::class.java).setAction(ACTION_RELOAD))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FirewallVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
