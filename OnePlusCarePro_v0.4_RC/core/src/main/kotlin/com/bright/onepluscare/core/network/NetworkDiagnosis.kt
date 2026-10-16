package com.bright.onepluscare.core.network

import com.bright.onepluscare.core.model.Transport

enum class ProbeStatus {
    /** The probe got the expected answer. */
    OK,

    /** A TCP RST came back: the host is reachable even though the port is closed (used for gateways). */
    REACHABLE_REFUSED,
    FAILED,
    TIMEOUT,

    /** Not attempted (no network, or prerequisite failed). */
    NOT_RUN,
}

data class Probe(val status: ProbeStatus, val millis: Long? = null)

enum class PrivateDnsMode { OFF, OPPORTUNISTIC, STRICT, UNKNOWN }

data class NetworkSnapshot(
    val connected: Boolean,
    val transports: Set<Transport>,
    val vpnActive: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
    val privateDns: PrivateDnsMode,
    val dnsServerCount: Int,
    val gatewayKnown: Boolean,
    val gateway: Probe,
    val dns: Probe,
    /** HTTPS GET to the platform connectivity-check endpoint expecting HTTP 204. */
    val https: Probe,
)

/** One row of the layered Network Doctor view, bottom (link) to top (validation). */
enum class NetworkLayer { LINK, GATEWAY, DNS, INTERNET, VALIDATION, VPN, PRIVATE_DNS }

enum class LayerStatus { OK, PROBLEM, UNKNOWN, NOT_APPLICABLE }

data class LayerResult(val layer: NetworkLayer, val status: LayerStatus, val millis: Long? = null)

enum class NetworkVerdict {
    OFFLINE,
    CAPTIVE_PORTAL,
    LOCAL_NETWORK_ONLY,
    PRIVATE_DNS_FAILURE,
    DNS_FAILURE,
    INTERNET_UNREACHABLE,
    VPN_SUSPECTED,
    NOT_VALIDATED,
    SLOW,
    HEALTHY,
}

data class NetworkDiagnosis(val verdict: NetworkVerdict, val layers: List<LayerResult>)

object NetworkDiagnoser {
    const val SLOW_HTTPS_MS = 800L
    const val SLOW_DNS_MS = 400L

    fun diagnose(s: NetworkSnapshot): NetworkDiagnosis {
        val layers = layers(s)
        val verdict = when {
            !s.connected -> NetworkVerdict.OFFLINE
            s.captivePortal -> NetworkVerdict.CAPTIVE_PORTAL
            s.https.status == ProbeStatus.OK && s.dns.status == ProbeStatus.OK -> when {
                isSlow(s) -> NetworkVerdict.SLOW
                !s.validated -> NetworkVerdict.NOT_VALIDATED
                else -> NetworkVerdict.HEALTHY
            }
            s.dns.status.failed() && s.privateDns == PrivateDnsMode.STRICT -> NetworkVerdict.PRIVATE_DNS_FAILURE
            s.dns.status.failed() && s.gateway.reached() -> NetworkVerdict.DNS_FAILURE
            s.dns.status.failed() && s.vpnActive -> NetworkVerdict.VPN_SUSPECTED
            // Gateway answered nothing and DNS fails: the uplink itself is down.
            s.dns.status.failed() && s.gatewayKnown && s.gateway.status.failed() -> NetworkVerdict.INTERNET_UNREACHABLE
            s.dns.status.failed() -> NetworkVerdict.DNS_FAILURE
            s.https.status.failed() && s.gateway.reached() -> NetworkVerdict.LOCAL_NETWORK_ONLY
            s.https.status.failed() && s.vpnActive -> NetworkVerdict.VPN_SUSPECTED
            s.https.status.failed() -> NetworkVerdict.INTERNET_UNREACHABLE
            !s.validated -> NetworkVerdict.NOT_VALIDATED
            else -> NetworkVerdict.HEALTHY
        }
        return NetworkDiagnosis(verdict, layers)
    }

    private fun isSlow(s: NetworkSnapshot): Boolean =
        (s.https.millis ?: 0L) > SLOW_HTTPS_MS || (s.dns.millis ?: 0L) > SLOW_DNS_MS

    private fun layers(s: NetworkSnapshot): List<LayerResult> = listOf(
        LayerResult(NetworkLayer.LINK, if (s.connected) LayerStatus.OK else LayerStatus.PROBLEM),
        LayerResult(
            NetworkLayer.GATEWAY,
            when {
                !s.connected -> LayerStatus.NOT_APPLICABLE
                !s.gatewayKnown -> LayerStatus.UNKNOWN
                s.gateway.reached() -> LayerStatus.OK
                // Many routers drop unsolicited TCP; a timeout does not prove the gateway is down.
                s.gateway.status == ProbeStatus.TIMEOUT -> LayerStatus.UNKNOWN
                s.gateway.status == ProbeStatus.NOT_RUN -> LayerStatus.UNKNOWN
                else -> LayerStatus.PROBLEM
            },
            s.gateway.millis,
        ),
        LayerResult(NetworkLayer.DNS, probeLayer(s.connected, s.dns), s.dns.millis),
        LayerResult(NetworkLayer.INTERNET, probeLayer(s.connected, s.https), s.https.millis),
        LayerResult(
            NetworkLayer.VALIDATION,
            when {
                !s.connected -> LayerStatus.NOT_APPLICABLE
                s.validated -> LayerStatus.OK
                else -> LayerStatus.PROBLEM
            },
        ),
        LayerResult(NetworkLayer.VPN, if (s.vpnActive) LayerStatus.OK else LayerStatus.NOT_APPLICABLE),
        LayerResult(
            NetworkLayer.PRIVATE_DNS,
            when (s.privateDns) {
                PrivateDnsMode.OFF -> LayerStatus.NOT_APPLICABLE
                PrivateDnsMode.UNKNOWN -> LayerStatus.UNKNOWN
                else -> if (s.dns.status.failed()) LayerStatus.PROBLEM else LayerStatus.OK
            },
        ),
    )

    private fun probeLayer(connected: Boolean, p: Probe): LayerStatus = when {
        !connected || p.status == ProbeStatus.NOT_RUN -> LayerStatus.NOT_APPLICABLE
        p.status == ProbeStatus.OK -> LayerStatus.OK
        else -> LayerStatus.PROBLEM
    }

    private fun ProbeStatus.failed() = this == ProbeStatus.FAILED || this == ProbeStatus.TIMEOUT
    private fun Probe.reached() = status == ProbeStatus.OK || status == ProbeStatus.REACHABLE_REFUSED

    /** Classify a TCP connect outcome to the gateway. */
    fun classifyGatewayError(message: String?, timedOut: Boolean): ProbeStatus = when {
        timedOut -> ProbeStatus.TIMEOUT
        message != null && (message.contains("ECONNREFUSED") || message.contains("refused", ignoreCase = true)) -> ProbeStatus.REACHABLE_REFUSED
        else -> ProbeStatus.FAILED
    }
}
