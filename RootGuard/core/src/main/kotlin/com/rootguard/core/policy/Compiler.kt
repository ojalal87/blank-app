package com.rootguard.core.policy

import com.rootguard.core.model.Action
import com.rootguard.core.model.Attribution
import com.rootguard.core.model.CapabilityId
import com.rootguard.core.model.DestinationType
import com.rootguard.core.model.Layer
import com.rootguard.core.model.NetworkScope
import com.rootguard.core.model.ProbeResult
import com.rootguard.core.model.Protocol
import com.rootguard.core.net.Cidr
import kotlinx.serialization.Serializable
import java.security.MessageDigest

enum class Verdict { RETURN, DENY }

/**
 * One backend-neutral match in the owned chain. ALLOW compiles to RETURN (hand the packet back to
 * OUTPUT so Android's own netd chains still apply); DENY rejects. First match wins.
 */
@Serializable
data class CompiledRule(
    val family: Int,
    val uidStart: Int? = null,
    val uidEnd: Int? = null,
    val outInterface: String? = null,
    val destination: String? = null,
    val protocol: Protocol = Protocol.ANY,
    val portFrom: Int? = null,
    val portTo: Int? = null,
    val verdict: Verdict,
    val sourceRuleId: String,
) {
    /** Canonical tuple used for hashing and for comparing kernel read-back. sourceRuleId is not part of it. */
    fun tuple(): String = listOf(
        "f=$family",
        "u=" + (uidStart?.let { "$it-$uidEnd" } ?: "*"),
        "o=" + (outInterface ?: "*"),
        "d=" + (destination ?: "*"),
        "p=" + protocol.name,
        "dp=" + (portFrom?.let { "$it-$portTo" } ?: "*"),
        "v=" + verdict.name,
    ).joinToString("|")
}

@Serializable
data class ChainPlan(val family: Int, val rules: List<CompiledRule>)

@Serializable
data class Plan(
    val revisionId: String,
    val expectedRevision: String?,
    val canonicalHash: String,
    val compilerVersion: String,
    val capabilitySnapshotId: String,
    val chains: List<ChainPlan>,
)

enum class PreviewNote {
    // warnings
    SHARED_UID, RULE_AFFECTS_ALL_UIDS, DEFAULT_DENY_SCOPE, ISOLATED_AND_SANDBOX_UIDS_NOT_COVERED,
    DNS_VIA_SYSTEM_RESOLVER, ENCRYPTED_DNS_NOT_VISIBLE, VPN_PATH_UNVERIFIED, IPV6_NOT_ENFORCED,
    OVERRIDE_BEATS_THREAT, EXPIRED_RULE_SKIPPED, CONFLICTING_FIREWALL, EXISTING_CONNECTIONS_UNVERIFIED,
    LOOPBACK_ALWAYS_ALLOWED, EMERGENCY_ISOLATION_ACTIVE,
    // unsupported (plan is null when any of these is present)
    DOMAIN_RULES_UNSUPPORTED, TETHER_UNSUPPORTED, LAN_WITH_DESTINATION_UNSUPPORTED, NO_WIFI_INTERFACES,
    NO_MOBILE_INTERFACES, OWNER_MATCH_UNAVAILABLE, IPV6_REQUIRED_FOR_DENY_DEFAULT, INVALID_POLICY, NOT_ROOT,
}

@Serializable
data class PreviewItem(val note: PreviewNote, val ruleId: String? = null, val detail: String? = null)

data class Preview(val plan: Plan?, val warnings: List<PreviewItem>, val unsupported: List<PreviewItem>, val issues: List<PolicyIssue>)

/**
 * Deterministic policy → plan compiler. Precedence (first match wins in the chain):
 * 1 emergency isolation (its ALLOW exceptions, then deny all app UIDs);
 * 2 OVERRIDE layer; 3 THREAT layer; 4 USER layer (priority desc, DENY before ALLOW on ties, rule id);
 * 5 profile default for the user's app UID range.
 */
object PolicyCompiler {
    const val VERSION = "rg-compiler-1"
    const val APP_UID_FIRST = 10000
    const val APP_UID_LAST = 19999
    const val PER_USER_RANGE = 100000

    private val LAN_V4 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")
    private val LAN_V6 = listOf("fe80::/10", "fc00::/7")

    fun preview(policy: Policy, principals: Collection<PrincipalRef>, probe: ProbeResult, capabilitySnapshotId: String, expectedRevision: String?, nowMs: Long): Preview {
        val warnings = ArrayList<PreviewItem>()
        val unsupported = ArrayList<PreviewItem>()
        val issues = PolicyValidator.validate(policy, principals)
        if (issues.isNotEmpty()) unsupported += PreviewItem(PreviewNote.INVALID_POLICY)
        if (!probe.has(CapabilityId.ROOT)) unsupported += PreviewItem(PreviewNote.NOT_ROOT)
        if (!probe.has(CapabilityId.OWNER_MATCH_V4)) unsupported += PreviewItem(PreviewNote.OWNER_MATCH_UNAVAILABLE)
        val v6 = probe.has(CapabilityId.IPTABLES_V6) && probe.has(CapabilityId.OWNER_MATCH_V6) && probe.has(CapabilityId.RESTORE_NOFLUSH_V6)
        val families = if (v6) listOf(4, 6) else listOf(4)
        if (!v6) {
            if (policy.defaultAction == Action.DENY || policy.emergencyIsolation) unsupported += PreviewItem(PreviewNote.IPV6_REQUIRED_FOR_DENY_DEFAULT)
            else warnings += PreviewItem(PreviewNote.IPV6_NOT_ENFORCED)
        }
        if (probe.foreignChains.isNotEmpty()) warnings += PreviewItem(PreviewNote.CONFLICTING_FIREWALL, detail = probe.foreignChains.joinToString(","))
        warnings += PreviewItem(PreviewNote.LOOPBACK_ALWAYS_ALLOWED)
        warnings += PreviewItem(PreviewNote.DNS_VIA_SYSTEM_RESOLVER)
        warnings += PreviewItem(PreviewNote.EXISTING_CONNECTIONS_UNVERIFIED)

        val byId = principals.associateBy { it.principalId }
        val active = policy.rules.filter { r ->
            if (!r.enabled) return@filter false
            if (r.expiresMs != null && r.expiresMs <= nowMs) {
                warnings += PreviewItem(PreviewNote.EXPIRED_RULE_SKIPPED, r.id); return@filter false
            }
            true
        }
        for (r in active) {
            val p = r.principalId?.let(byId::get)
            if (r.principalId == null) warnings += PreviewItem(PreviewNote.RULE_AFFECTS_ALL_UIDS, r.id)
            if (p?.attribution == Attribution.SHARED) warnings += PreviewItem(PreviewNote.SHARED_UID, r.id, p.packages.joinToString(","))
            if (r.destinationType == DestinationType.DOMAIN_SUFFIX) unsupported += PreviewItem(PreviewNote.DOMAIN_RULES_UNSUPPORTED, r.id)
            if (r.network == NetworkScope.TETHER) unsupported += PreviewItem(PreviewNote.TETHER_UNSUPPORTED, r.id)
            if (r.network == NetworkScope.LAN && r.destinationType != DestinationType.ANY) unsupported += PreviewItem(PreviewNote.LAN_WITH_DESTINATION_UNSUPPORTED, r.id)
            if (r.network == NetworkScope.WIFI && probe.wifiInterfaces.isEmpty()) unsupported += PreviewItem(PreviewNote.NO_WIFI_INTERFACES, r.id)
            if (r.network == NetworkScope.MOBILE && probe.mobileInterfaces.isEmpty()) unsupported += PreviewItem(PreviewNote.NO_MOBILE_INTERFACES, r.id)
            if (r.network == NetworkScope.VPN && !probe.has(CapabilityId.VPN_PATH)) warnings += PreviewItem(PreviewNote.VPN_PATH_UNVERIFIED, r.id)
        }
        val ordered = order(active.filter { it.layer != Layer.EMERGENCY }, policy.emergencyIsolation)
        val firstThreat = ordered.indexOfFirst { it.layer == Layer.THREAT && it.action == Action.DENY }
        if (firstThreat >= 0 && ordered.take(firstThreat).any { it.layer == Layer.OVERRIDE && it.action == Action.ALLOW }) {
            warnings += PreviewItem(PreviewNote.OVERRIDE_BEATS_THREAT)
        }
        if (policy.defaultAction == Action.DENY || policy.emergencyIsolation) {
            warnings += PreviewItem(PreviewNote.DEFAULT_DENY_SCOPE, detail = appRange(policy.androidUserId).let { "${it.first}-${it.last}" })
            warnings += PreviewItem(PreviewNote.ISOLATED_AND_SANDBOX_UIDS_NOT_COVERED)
        }
        if (policy.emergencyIsolation) warnings += PreviewItem(PreviewNote.EMERGENCY_ISOLATION_ACTIVE)

        if (unsupported.isNotEmpty()) return Preview(null, warnings.distinct(), unsupported.distinct(), issues)

        val chains = families.map { fam ->
            val out = ArrayList<CompiledRule>()
            out += CompiledRule(fam, outInterface = "lo", verdict = Verdict.RETURN, sourceRuleId = "infra:loopback")
            if (policy.emergencyIsolation) {
                active.filter { it.layer == Layer.EMERGENCY }.sortedWith(RULE_ORDER).forEach { out += expand(it, fam, byId, probe) }
                val range = appRange(policy.androidUserId)
                out += CompiledRule(fam, range.first, range.last, verdict = Verdict.DENY, sourceRuleId = "infra:emergency")
            } else {
                ordered.forEach { out += expand(it, fam, byId, probe) }
                if (policy.defaultAction == Action.DENY) {
                    val range = appRange(policy.androidUserId)
                    out += CompiledRule(fam, range.first, range.last, verdict = Verdict.DENY, sourceRuleId = "infra:default")
                }
            }
            ChainPlan(fam, out)
        }
        val plan = Plan(policy.revisionId, expectedRevision, hash(chains), VERSION, capabilitySnapshotId, chains)
        return Preview(plan, warnings.distinct(), emptyList(), issues)
    }

    fun appRange(userId: Int): IntRange = (userId * PER_USER_RANGE + APP_UID_FIRST)..(userId * PER_USER_RANGE + APP_UID_LAST)

    private val RULE_ORDER = compareByDescending<Rule> { it.priority }.thenBy { if (it.action == Action.DENY) 0 else 1 }.thenBy { it.id }

    private fun order(rules: List<Rule>, emergency: Boolean): List<Rule> {
        if (emergency) return emptyList()
        val layerRank = mapOf(Layer.OVERRIDE to 0, Layer.THREAT to 1, Layer.USER to 2)
        return rules.sortedWith(compareBy<Rule> { layerRank.getValue(it.layer) }.then(RULE_ORDER))
    }

    private fun expand(r: Rule, fam: Int, byId: Map<String, PrincipalRef>, probe: ProbeResult): List<CompiledRule> {
        val dest = when (r.destinationType) {
            DestinationType.IP, DestinationType.CIDR -> Cidr.parse(r.destinationValue!!)!!
            else -> null
        }
        if (dest != null && dest.family != fam) return emptyList()
        if (r.protocol == Protocol.ICMP && fam != 4) return emptyList()
        if (r.protocol == Protocol.ICMPV6 && fam != 6) return emptyList()
        val uid = r.principalId?.let { byId.getValue(it).androidUid }
        val ifaces: List<String?> = when (r.network) {
            NetworkScope.ANY, NetworkScope.LAN -> listOf(null)
            NetworkScope.WIFI -> probe.wifiInterfaces
            NetworkScope.MOBILE -> probe.mobileInterfaces
            NetworkScope.VPN -> listOf("tun+")
            NetworkScope.TETHER -> emptyList()
        }
        val dests: List<String?> = when {
            r.network == NetworkScope.LAN -> (if (fam == 4) LAN_V4 else LAN_V6)
            dest != null -> listOf(dest.toString())
            else -> listOf(null)
        }
        val verdict = if (r.action == Action.ALLOW) Verdict.RETURN else Verdict.DENY
        return ifaces.flatMap { i ->
            dests.map { d -> CompiledRule(fam, uid, uid, i, d, r.protocol, r.portFrom, r.portTo, verdict, r.id) }
        }
    }

    fun hash(chains: List<ChainPlan>): String {
        val md = MessageDigest.getInstance("SHA-256")
        chains.sortedBy { it.family }.forEach { c ->
            md.update("chain:${c.family}\n".toByteArray())
            c.rules.forEach { md.update((it.tuple() + "\n").toByteArray()) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hash of exactly one family's tuples, as the helper reports it from kernel read-back. */
    fun familyHash(family: Int, rules: List<CompiledRule>): String = hash(listOf(ChainPlan(family, rules)))
}
