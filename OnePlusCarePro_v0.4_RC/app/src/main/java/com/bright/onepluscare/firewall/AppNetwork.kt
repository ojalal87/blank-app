package com.bright.onepluscare.firewall

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * While the firewall has nothing to block on the current network, its tunnel holds only this app (so the
 * VPN stays up for the next switch). This app's own requests (Network Doctor, list updates) must then go to
 * the real network explicitly; the self-only tunnel allows that bypass.
 */
object AppNetwork {
    fun forOwnTraffic(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        val selfRouted = FirewallStore.status.value.let { it.running && it.selfOnly }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true || !selfRouted) return active
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
        return (candidates.firstOrNull { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } ?: candidates.firstOrNull())?.first ?: active
    }
}
