package com.rootguard.core.policy

import com.rootguard.core.model.Action
import com.rootguard.core.model.Attribution
import com.rootguard.core.model.DestinationType
import com.rootguard.core.model.Ids
import com.rootguard.core.model.Layer
import com.rootguard.core.model.Mode
import com.rootguard.core.model.NetworkScope
import com.rootguard.core.model.Protocol
import com.rootguard.core.net.Cidr
import com.rootguard.core.net.Domains
import kotlinx.serialization.Serializable

/** A time-bounded UID assignment. Rules target principals, never package names. */
@Serializable
data class PrincipalRef(
    val principalId: String,
    val userSerial: Long,
    val androidUid: Int,
    val attribution: Attribution,
    val packages: List<String>,
)

@Serializable
data class Rule(
    val id: String,
    /** null = every UID (system and root included); the preview warns about this. */
    val principalId: String?,
    val action: Action,
    val layer: Layer = Layer.USER,
    val network: NetworkScope = NetworkScope.ANY,
    val destinationType: DestinationType = DestinationType.ANY,
    val destinationValue: String? = null,
    val protocol: Protocol = Protocol.ANY,
    val portFrom: Int? = null,
    val portTo: Int? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
    /** Wall-clock expiry for persisted rules. Boot-scoped temporary overrides use monotonic time in the app. */
    val expiresMs: Long? = null,
)

@Serializable
data class Policy(
    val revisionId: String,
    val parentRevisionId: String?,
    val profileId: String,
    val mode: Mode,
    val defaultAction: Action,
    /** Android user whose app UID range (base+10000..base+19999) the default action covers. */
    val androidUserId: Int = 0,
    val emergencyIsolation: Boolean = false,
    val rules: List<Rule>,
)

enum class PolicyError {
    BAD_REVISION_ID, BAD_RULE_ID, DUPLICATE_RULE_ID, TOO_MANY_RULES, UNKNOWN_PRINCIPAL,
    DESTINATION_VALUE_MISMATCH, BAD_IP, BAD_CIDR, BAD_DOMAIN, PORTS_REQUIRE_TCP_OR_UDP, BAD_PORT_RANGE,
    ICMP_FAMILY_MISMATCH, EMERGENCY_RULE_MUST_ALLOW, BAD_USER_ID,
}

data class PolicyIssue(val error: PolicyError, val ruleId: String?)

/** Mirrors every CHECK in control_v1.sql plus the invariants SQLite cannot express. */
object PolicyValidator {
    const val MAX_RULES = 2000

    fun validate(policy: Policy, principals: Collection<PrincipalRef>): List<PolicyIssue> {
        val out = ArrayList<PolicyIssue>()
        if (!Ids.isSafeId(policy.revisionId)) out += PolicyIssue(PolicyError.BAD_REVISION_ID, null)
        if (policy.androidUserId !in 0..999) out += PolicyIssue(PolicyError.BAD_USER_ID, null)
        if (policy.rules.size > MAX_RULES) out += PolicyIssue(PolicyError.TOO_MANY_RULES, null)
        val known = principals.mapTo(HashSet()) { it.principalId }
        val seen = HashSet<String>()
        for (r in policy.rules) {
            if (!Ids.isSafeId(r.id)) out += PolicyIssue(PolicyError.BAD_RULE_ID, r.id.take(64))
            if (!seen.add(r.id)) out += PolicyIssue(PolicyError.DUPLICATE_RULE_ID, r.id)
            if (r.principalId != null && r.principalId !in known) out += PolicyIssue(PolicyError.UNKNOWN_PRINCIPAL, r.id)
            val hasValue = r.destinationValue != null
            if ((r.destinationType == DestinationType.ANY) == hasValue) out += PolicyIssue(PolicyError.DESTINATION_VALUE_MISMATCH, r.id)
            when (r.destinationType) {
                DestinationType.IP -> r.destinationValue?.let { if (Cidr.parse(it)?.let { c -> c.prefix == (if (c.family == 4) 32 else 128) } != true) out += PolicyIssue(PolicyError.BAD_IP, r.id) }
                DestinationType.CIDR -> r.destinationValue?.let { if (Cidr.parse(it) == null || '/' !in it) out += PolicyIssue(PolicyError.BAD_CIDR, r.id) }
                DestinationType.DOMAIN_SUFFIX -> r.destinationValue?.let { if (Domains.normalize(it) != it) out += PolicyIssue(PolicyError.BAD_DOMAIN, r.id) }
                DestinationType.ANY -> Unit
            }
            val ports = r.portFrom != null || r.portTo != null
            if (ports) {
                if (r.protocol != Protocol.TCP && r.protocol != Protocol.UDP) out += PolicyIssue(PolicyError.PORTS_REQUIRE_TCP_OR_UDP, r.id)
                val f = r.portFrom; val t = r.portTo
                if (f == null || t == null || f !in 1..65535 || t !in 1..65535 || f > t) out += PolicyIssue(PolicyError.BAD_PORT_RANGE, r.id)
            }
            val fam = r.destinationValue?.takeIf { r.destinationType == DestinationType.IP || r.destinationType == DestinationType.CIDR }?.let { Cidr.parse(it)?.family }
            if ((r.protocol == Protocol.ICMP && fam == 6) || (r.protocol == Protocol.ICMPV6 && fam == 4)) out += PolicyIssue(PolicyError.ICMP_FAMILY_MISMATCH, r.id)
            if (r.layer == Layer.EMERGENCY && r.action != Action.ALLOW) out += PolicyIssue(PolicyError.EMERGENCY_RULE_MUST_ALLOW, r.id)
        }
        return out
    }
}
