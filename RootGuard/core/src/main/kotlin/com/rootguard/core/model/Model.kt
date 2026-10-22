package com.rootguard.core.model

import kotlinx.serialization.Serializable

// Enum names are the persisted values of control_v1.sql; never rename an entry.
enum class Support { SUPPORTED, UNSUPPORTED, DENIED, UNVERIFIED }
enum class EnforcementHealth { OFF, ACTIVE, DEGRADED, UNKNOWN }
enum class Mode { OBSERVE, BALANCED, PRIVACY, STRICT, DATA_SAVER, CUSTOM }
enum class Action { ALLOW, DENY }
enum class Attribution { EXACT, SHARED, UNKNOWN }
enum class Layer { EMERGENCY, OVERRIDE, THREAT, USER }
enum class NetworkScope { ANY, WIFI, MOBILE, VPN, LAN, TETHER }
enum class DestinationType { ANY, IP, CIDR, DOMAIN_SUFFIX }
enum class Protocol { ANY, TCP, UDP, ICMP, ICMPV6 }
enum class BehaviorResponse { LOG, NOTIFY, TEMP_CONTAIN }
enum class FailureMode { PRESERVE_LAST_GOOD, STRICT_FAIL_CLOSED }
enum class Phase { PREPARED, APPLYING, VERIFIED, COMMITTED, ROLLED_BACK, FAILED }

/** Every capability the probe reports. Enforcement and monitoring coverage are separate entries. */
enum class CapabilityId {
    ROOT,
    SELINUX_ENFORCING,
    IPTABLES_V4,
    IPTABLES_V6,
    RESTORE_NOFLUSH_V4,
    RESTORE_NOFLUSH_V6,
    XTABLES_WAIT,
    OWNER_MATCH_V4,
    OWNER_MATCH_V6,
    REJECT_V4,
    REJECT_V6,
    WIFI_INTERFACES,
    MOBILE_INTERFACES,
    VPN_PATH,
    LAN_SCOPE,
    TETHER_FORWARD,
    DOMAIN_RULES,
    NFTABLES,
    PER_FLOW_EBPF,
    FLOW_METADATA,
    NO_CONFLICTING_FIREWALL,
}

@Serializable
data class Capability(val id: CapabilityId, val support: Support, val reason: String)

@Serializable
data class ProbeResult(
    val bootId: String,
    val probeVersion: String,
    val recordedMs: Long,
    val capabilities: List<Capability>,
    /** Interface patterns (e.g. "wlan+") the backend may use for WIFI / MOBILE scopes. */
    val wifiInterfaces: List<String> = emptyList(),
    val mobileInterfaces: List<String> = emptyList(),
    /** Names of user chains in filter/OUTPUT that belong to other firewalls (coexistence report). */
    val foreignChains: List<String> = emptyList(),
) {
    fun support(id: CapabilityId): Support = capabilities.firstOrNull { it.id == id }?.support ?: Support.UNVERIFIED
    fun has(id: CapabilityId): Boolean = support(id) == Support.SUPPORTED
}

object Ids {
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val SAFE_ID = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    fun isUuid(s: String?): Boolean = s != null && UUID.matches(s)
    /** Rule/revision IDs: application-generated, but validated so they can appear in logs and plans safely. */
    fun isSafeId(s: String?): Boolean = s != null && SAFE_ID.matches(s)
}
