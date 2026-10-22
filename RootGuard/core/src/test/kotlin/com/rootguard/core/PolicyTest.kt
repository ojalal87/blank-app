package com.rootguard.core

import com.rootguard.core.model.Action
import com.rootguard.core.model.CapabilityId
import com.rootguard.core.model.DestinationType
import com.rootguard.core.model.Layer
import com.rootguard.core.model.NetworkScope
import com.rootguard.core.model.Protocol
import com.rootguard.core.policy.PolicyCompiler
import com.rootguard.core.policy.PolicyError
import com.rootguard.core.policy.PolicyValidator
import com.rootguard.core.policy.PreviewNote
import com.rootguard.core.policy.Rule
import com.rootguard.core.policy.Verdict
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyTest {
    private fun preview(vararg rules: Rule, default: Action = Action.ALLOW, emergency: Boolean = false, probe: com.rootguard.core.model.ProbeResult = Fx.probe()) =
        PolicyCompiler.preview(Fx.policy(*rules, default = default, emergency = emergency), Fx.principals, probe, "cap-1", "rev-1", nowMs = 1000)

    @Test
    fun `validator mirrors schema checks`() {
        val bad = listOf(
            Rule("r1", "p-browser", Action.DENY, protocol = Protocol.TCP, portFrom = 100, portTo = 90),
            Rule("r2", "nobody", Action.DENY),
            Rule("r3", null, Action.DENY, destinationType = DestinationType.ANY, destinationValue = "1.2.3.4"),
            Rule("r4", null, Action.DENY, destinationType = DestinationType.CIDR, destinationValue = "10.0.0.1/8"),
            Rule("r5", null, Action.DENY, portFrom = 80, portTo = 80),
            Rule("r6", null, Action.DENY, layer = Layer.EMERGENCY),
            Rule("r6", null, Action.ALLOW),
            Rule("bad id!", null, Action.ALLOW),
        )
        val errs = PolicyValidator.validate(Fx.policy(*bad.toTypedArray()), Fx.principals).map { it.error }.toSet()
        assertEquals(
            setOf(PolicyError.BAD_PORT_RANGE, PolicyError.UNKNOWN_PRINCIPAL, PolicyError.DESTINATION_VALUE_MISMATCH, PolicyError.BAD_CIDR,
                PolicyError.PORTS_REQUIRE_TCP_OR_UDP, PolicyError.EMERGENCY_RULE_MUST_ALLOW, PolicyError.DUPLICATE_RULE_ID, PolicyError.BAD_RULE_ID),
            errs,
        )
    }

    @Test
    fun `per-app wifi deny compiles for both families with loopback first`() {
        val p = preview(Rule("deny-wifi", "p-browser", Action.DENY, network = NetworkScope.WIFI))
        val plan = assertNotNull(p.plan)
        assertEquals(listOf(4, 6), plan.chains.map { it.family })
        val v4 = plan.chains[0].rules
        assertEquals("infra:loopback", v4[0].sourceRuleId)
        assertEquals(Verdict.DENY, v4[1].verdict)
        assertEquals(10123, v4[1].uidStart)
        assertEquals("wlan+", v4[1].outInterface)
        assertEquals(PolicyCompiler.hash(plan.chains), plan.canonicalHash)
    }

    @Test
    fun `precedence - override, threat, user by priority, deny wins ties, then default`() {
        val p = preview(
            Rule("u-allow", "p-browser", Action.ALLOW, priority = 5),
            Rule("u-deny", "p-browser", Action.DENY, priority = 5),
            Rule("u-high", "p-browser", Action.ALLOW, priority = 9),
            Rule("t", null, Action.DENY, layer = Layer.THREAT, destinationType = DestinationType.IP, destinationValue = "203.0.113.9"),
            Rule("o", "p-browser", Action.ALLOW, layer = Layer.OVERRIDE),
            default = Action.DENY,
        )
        val ids = assertNotNull(p.plan).chains.first { it.family == 4 }.rules.map { it.sourceRuleId }
        assertEquals(listOf("infra:loopback", "o", "t", "u-high", "u-deny", "u-allow", "infra:default"), ids)
        assertTrue(p.warnings.any { it.note == PreviewNote.OVERRIDE_BEATS_THREAT })
        assertTrue(p.warnings.any { it.note == PreviewNote.DEFAULT_DENY_SCOPE && it.detail == "10000-19999" })
        val v6ids = p.plan!!.chains.first { it.family == 6 }.rules.map { it.sourceRuleId }
        assertTrue("t" !in v6ids, "an IPv4 destination never lands in the IPv6 chain")
    }

    @Test
    fun `compilation is deterministic regardless of rule input order`() {
        val a = Rule("a", "p-browser", Action.DENY, network = NetworkScope.MOBILE)
        val b = Rule("b", "p-shared", Action.DENY)
        assertEquals(preview(a, b).plan!!.canonicalHash, preview(b, a).plan!!.canonicalHash)
    }

    @Test
    fun `unexpressible rules are rejected in preview, not silently approximated`() {
        val d = preview(Rule("d", "p-browser", Action.DENY, destinationType = DestinationType.DOMAIN_SUFFIX, destinationValue = "example.com"))
        assertNull(d.plan)
        assertTrue(d.unsupported.any { it.note == PreviewNote.DOMAIN_RULES_UNSUPPORTED })
        assertNull(preview(Rule("t", "p-browser", Action.DENY, network = NetworkScope.TETHER)).plan)
        assertNull(preview(Rule("x", "p-browser", Action.DENY), probe = Fx.probe(true, CapabilityId.ROOT)).plan)
    }

    @Test
    fun `missing ipv6 blocks default-deny but only warns for allow-default`() {
        val r = Rule("x", "p-browser", Action.DENY)
        val allow = preview(r, probe = Fx.probe(v6 = false))
        assertEquals(listOf(4), allow.plan!!.chains.map { it.family })
        assertTrue(allow.warnings.any { it.note == PreviewNote.IPV6_NOT_ENFORCED })
        assertNull(preview(r, default = Action.DENY, probe = Fx.probe(v6 = false)).plan)
    }

    @Test
    fun `shared uid and all-uid rules are called out`() {
        val p = preview(Rule("s", "p-shared", Action.DENY), Rule("all", null, Action.DENY, protocol = Protocol.UDP, portFrom = 53, portTo = 53))
        assertTrue(p.warnings.any { it.note == PreviewNote.SHARED_UID && it.detail == "com.vendor.a,com.vendor.b" })
        assertTrue(p.warnings.any { it.note == PreviewNote.RULE_AFFECTS_ALL_UIDS })
    }

    @Test
    fun `emergency isolation keeps only its exceptions`() {
        val p = preview(Rule("keep", "p-browser", Action.ALLOW, layer = Layer.EMERGENCY), Rule("u", "p-shared", Action.ALLOW), emergency = true)
        val ids = p.plan!!.chains[0].rules.map { it.sourceRuleId }
        assertEquals(listOf("infra:loopback", "keep", "infra:emergency"), ids)
    }

    @Test
    fun `lan scope expands to private ranges per family and expired rules are skipped`() {
        val p = preview(Rule("lan", "p-browser", Action.DENY, network = NetworkScope.LAN), Rule("old", "p-shared", Action.DENY, expiresMs = 10))
        val v4 = p.plan!!.chains[0].rules.filter { it.sourceRuleId == "lan" }.map { it.destination }
        assertEquals(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"), v4)
        assertTrue(p.plan!!.chains.flatMap { it.rules }.none { it.sourceRuleId == "old" })
        assertTrue(p.warnings.any { it.note == PreviewNote.EXPIRED_RULE_SKIPPED && it.ruleId == "old" })
    }
}
