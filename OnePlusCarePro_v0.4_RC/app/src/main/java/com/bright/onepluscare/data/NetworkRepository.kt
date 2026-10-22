package com.bright.onepluscare.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.bright.onepluscare.core.network.NetworkDiagnoser
import com.bright.onepluscare.core.network.NetworkDiagnosis
import com.bright.onepluscare.core.network.NetworkSnapshot
import com.bright.onepluscare.core.network.PrivateDnsMode
import com.bright.onepluscare.core.network.Probe
import com.bright.onepluscare.core.network.ProbeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class NetworkResult(val snapshot: NetworkSnapshot, val diagnosis: NetworkDiagnosis, val measuredAtMillis: Long)

/**
 * Layered network check bound to the *active* network (so a VPN or Wi-Fi/mobile switch is measured as the
 * system routes it). Probes:
 *  - Gateway: TCP connect to the default gateway on port 53. A connection *or* a TCP reset both prove the
 *    gateway answers; a timeout is reported as "unknown" because many routers silently drop such packets.
 *  - DNS: resolve the platform connectivity-check host through the network's resolver (includes Private DNS).
 *  - Internet: HTTPS GET to the same endpoint Android uses for validation, expecting HTTP 204. TLS is
 *    validated against system CAs only (network_security_config.xml). No user data is sent.
 * Nothing is persisted except a summary event in history; addresses are never exported.
 */
class NetworkRepository(private val context: Context) {

    suspend fun run(): NetworkResult = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // The firewall's self-only tunnel would otherwise be measured instead of the real network.
        val network = com.bright.onepluscare.firewall.AppNetwork.forOwnTraffic(context)
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val props = network?.let { cm.getLinkProperties(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val gatewayAddr = props?.let(::defaultGateway)
        // `connected` implies a non-null network (caps came from it).
        val active = network.takeIf { connected }
        val gateway = if (active != null && gatewayAddr != null) probeGateway(active, gatewayAddr) else Probe(ProbeStatus.NOT_RUN)
        val dns = if (active != null) probeDns(active) else Probe(ProbeStatus.NOT_RUN)
        val https = if (active != null && dns.status == ProbeStatus.OK) probeHttps(active) else Probe(ProbeStatus.NOT_RUN)

        val snapshot = NetworkSnapshot(
            connected = connected,
            transports = caps?.let(DeviceRepository::transports) ?: emptySet(),
            vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            privateDns = privateDns(props),
            dnsServerCount = props?.dnsServers?.size ?: 0,
            gatewayKnown = gatewayAddr != null,
            gateway = gateway,
            dns = dns,
            https = https,
        )
        NetworkResult(snapshot, NetworkDiagnoser.diagnose(snapshot), System.currentTimeMillis())
    }

    private fun privateDns(props: LinkProperties?): PrivateDnsMode {
        if (props == null || Build.VERSION.SDK_INT < 28) return PrivateDnsMode.UNKNOWN
        return when {
            !props.isPrivateDnsActive -> PrivateDnsMode.OFF
            props.privateDnsServerName != null -> PrivateDnsMode.STRICT
            else -> PrivateDnsMode.OPPORTUNISTIC
        }
    }

    private fun defaultGateway(props: LinkProperties): InetAddress? = props.routes
        .firstOrNull { r -> r.isDefaultRoute && r.gateway != null && !r.gateway!!.isAnyLocalAddress && r.gateway is Inet4Address }
        ?.gateway

    private fun probeGateway(network: Network, address: InetAddress): Probe {
        val start = SystemClock.elapsedRealtime()
        return try {
            network.socketFactory.createSocket().use { s -> s.connect(InetSocketAddress(address, 53), GATEWAY_TIMEOUT_MS) }
            Probe(ProbeStatus.OK, SystemClock.elapsedRealtime() - start)
        } catch (e: SocketTimeoutException) {
            Probe(ProbeStatus.TIMEOUT)
        } catch (e: Exception) {
            val status = NetworkDiagnoser.classifyGatewayError(e.message, false)
            Probe(status, if (status == ProbeStatus.REACHABLE_REFUSED) SystemClock.elapsedRealtime() - start else null)
        }
    }

    private fun probeDns(network: Network): Probe {
        val start = SystemClock.elapsedRealtime()
        return try {
            val addresses = network.getAllByName(PROBE_HOST)
            if (addresses.isNotEmpty()) Probe(ProbeStatus.OK, SystemClock.elapsedRealtime() - start) else Probe(ProbeStatus.FAILED)
        } catch (_: Exception) {
            val elapsed = SystemClock.elapsedRealtime() - start
            Probe(if (elapsed >= DNS_TIMEOUT_HINT_MS) ProbeStatus.TIMEOUT else ProbeStatus.FAILED)
        }
    }

    private fun probeHttps(network: Network): Probe {
        val start = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        return try {
            conn = network.openConnection(URL(PROBE_URL)) as HttpsURLConnection
            conn.connectTimeout = HTTPS_TIMEOUT_MS
            conn.readTimeout = HTTPS_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            val ms = SystemClock.elapsedRealtime() - start
            // Anything but 204 means a proxy/portal answered instead of the real endpoint.
            if (code == 204) Probe(ProbeStatus.OK, ms) else Probe(ProbeStatus.FAILED, ms)
        } catch (_: SocketTimeoutException) {
            Probe(ProbeStatus.TIMEOUT)
        } catch (_: Exception) {
            Probe(ProbeStatus.FAILED)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val PROBE_HOST = "connectivitycheck.gstatic.com"
        const val PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        const val GATEWAY_TIMEOUT_MS = 1_200
        const val HTTPS_TIMEOUT_MS = 5_000
        const val DNS_TIMEOUT_HINT_MS = 4_500L

    }
}
