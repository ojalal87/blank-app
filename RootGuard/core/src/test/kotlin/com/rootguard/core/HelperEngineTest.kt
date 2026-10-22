package com.rootguard.core

import com.rootguard.core.helper.HelperEngine
import com.rootguard.core.model.Action
import com.rootguard.core.model.CapabilityId
import com.rootguard.core.model.NetworkScope
import com.rootguard.core.model.Phase
import com.rootguard.core.model.ProbeResult
import com.rootguard.core.model.Support
import com.rootguard.core.policy.Plan
import com.rootguard.core.policy.PolicyCompiler
import com.rootguard.core.policy.Rule
import com.rootguard.core.protocol.ErrorCode
import com.rootguard.core.protocol.Operation
import com.rootguard.core.protocol.Payload
import com.rootguard.core.protocol.Request
import com.rootguard.core.protocol.Response
import com.rootguard.core.protocol.Result
import com.rootguard.core.protocol.decodeResponse
import com.rootguard.core.protocol.encode
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HelperEngineTest {
    private val ipt = FakeIptables()
    private val journal = MemJournal()
    private val engine = HelperEngine(ipt, FakeSys(), journal, "boot-1")
    private var now = 1_000L

    private fun call(op: Operation, payload: Payload = Payload.None, expected: String? = null): Response {
        now += 10
        val raw = Request(1, UUID.randomUUID().toString(), op, "boot-1", now + 30_000, expected, payload).encode()
        return assertNotNull(decodeResponse(engine.handle(raw, now)))
    }

    private fun probe(): ProbeResult = (call(Operation.PROBE).result as Result.Probe).probe

    private fun plan(probe: ProbeResult, rev: String, vararg rules: Rule, default: Action = Action.ALLOW): Plan =
        PolicyCompiler.preview(Fx.policy(*rules, default = default).copy(revisionId = rev), Fx.principals, probe, "cap", null, 0).plan!!

    private fun apply(p: Plan, expected: String?) = call(Operation.APPLY, Payload.PlanPayload(UUID.randomUUID().toString(), p), expected)
    private fun receipt(r: Response) = (r.result as Result.ReceiptResult).receipt

    @Test
    fun `probe uses a scratch chain only and reports interfaces`() {
        val p = probe()
        assertEquals(Support.SUPPORTED, p.support(CapabilityId.OWNER_MATCH_V6))
        assertEquals(listOf("wlan+"), p.wifiInterfaces)
        assertEquals(listOf("rmnet_data+", "rmnet+"), p.mobileInterfaces)
        assertEquals(Support.UNVERIFIED, p.support(CapabilityId.VPN_PATH))
        ipt.tables.values.forEach { t ->
            assertFalse("RG_PROBE" in t.chains)
            assertEquals(listOf("-j fw_OUTPUT", "-j bw_OUTPUT"), t.output)
        }
    }

    @Test
    fun `apply installs own chain and hook, verifies read-back, leaves netd chains alone`() {
        val p = plan(probe(), "rev-a", Rule("w", "p-browser", Action.DENY, network = NetworkScope.WIFI))
        val r = receipt(apply(p, null))
        assertEquals(Phase.VERIFIED, r.phase)
        assertEquals(p.canonicalHash, r.verifiedHash)
        ipt.tables.values.forEach { t ->
            assertEquals(listOf("-j RG_OUT", "-j fw_OUTPUT", "-j bw_OUTPUT"), t.output)
            assertEquals<List<String>?>(listOf("-j RETURN"), t.chains["fw_OUTPUT"])
        }
        val v = receipt(call(Operation.VERIFY, Payload.VerifyPayload("rev-a", p.canonicalHash)))
        assertEquals(Phase.VERIFIED, v.phase)
        // Re-apply a new revision: chain replaced, still exactly one hook.
        val p2 = plan(probe(), "rev-b", Rule("m", "p-shared", Action.DENY, network = NetworkScope.MOBILE))
        assertEquals(Phase.VERIFIED, receipt(apply(p2, "rev-a")).phase)
        assertEquals(1, ipt.tables.getValue(4).output.count { it == "-j RG_OUT" })
    }

    @Test
    fun `ipv6 failure after ipv4 succeeded is compensated back to the previous plan`() {
        val pr = probe()
        val p1 = plan(pr, "rev-a", Rule("w", "p-browser", Action.DENY))
        assertEquals(Phase.VERIFIED, receipt(apply(p1, null)).phase)
        val before = ipt.tables.mapValues { it.value.chains["RG_OUT"]!!.toList() }
        ipt.failRestore = 6
        val resp = apply(plan(pr, "rev-b", Rule("x", "p-shared", Action.DENY)), "rev-a")
        assertFalse(resp.ok)
        val r = receipt(resp)
        assertEquals(Phase.ROLLED_BACK, r.phase)
        assertTrue(r.compensated)
        assertEquals(before, ipt.tables.mapValues { it.value.chains["RG_OUT"]!!.toList() })
        assertEquals("rev-a", journal.j!!.revisionId)
    }

    @Test
    fun `rollback restores the plan that was active before the transaction`() {
        val pr = probe()
        assertEquals(Phase.VERIFIED, receipt(apply(plan(pr, "rev-a", Rule("w", "p-browser", Action.DENY)), null)).phase)
        val a = ipt.tables.getValue(4).chains["RG_OUT"]!!.toList()
        val tx = UUID.randomUUID().toString()
        call(Operation.APPLY, Payload.PlanPayload(tx, plan(pr, "rev-b", Rule("x", "p-shared", Action.DENY))), "rev-a")
        val rb = receipt(call(Operation.ROLLBACK, Payload.RollbackPayload(tx)))
        assertEquals(Phase.ROLLED_BACK, rb.phase)
        assertEquals("rev-a", rb.revisionId)
        assertEquals<List<String>?>(a, ipt.tables.getValue(4).chains["RG_OUT"])
    }

    @Test
    fun `recovery removes only RootGuard state and works without a probe`() {
        assertEquals(Phase.VERIFIED, receipt(apply(plan(probe(), "rev-a", Rule("w", "p-browser", Action.DENY), default = Action.DENY), null)).phase)
        val fresh = HelperEngine(ipt, FakeSys(), journal, "boot-1")
        now += 10
        val raw = Request(1, UUID.randomUUID().toString(), Operation.RECOVER_OWN_RULES, "boot-1", now + 1000, null, Payload.None).encode()
        val r = (decodeResponse(fresh.handle(raw, now))!!.result as Result.ReceiptResult).receipt
        assertEquals(Phase.COMMITTED, r.phase)
        ipt.tables.values.forEach { t ->
            assertFalse("RG_OUT" in t.chains)
            assertEquals(listOf("-j fw_OUTPUT", "-j bw_OUTPUT"), t.output)
        }
    }

    @Test
    fun `guards - no probe, stale revision, tampered plan, unsupported operation`() {
        val pr = Fx.probe()
        val p = plan(pr, "rev-a", Rule("w", "p-browser", Action.DENY))
        assertEquals(ErrorCode.CAPABILITY_STALE, apply(p, null).error)
        probe()
        assertEquals(Phase.VERIFIED, receipt(apply(p, null)).phase)
        assertEquals(ErrorCode.REVISION_CONFLICT, apply(plan(pr, "rev-b"), "rev-zzz").error)
        assertEquals(ErrorCode.INVALID_PLAN, apply(p.copy(canonicalHash = "0".repeat(64)), "rev-a").error)
        assertEquals(ErrorCode.UNSUPPORTED_OPERATION, call(Operation.SUBSCRIBE_METADATA).error)
        assertTrue(ipt.calls.none { argv -> argv.any { it == "-F" } && argv.last() !in setOf("RG_OUT", "RG_PROBE") })
    }

    @Test
    fun `owner match unavailable is reported, not assumed`() {
        ipt.ownerUnsupported = true
        val p = probe()
        assertEquals(Support.UNSUPPORTED, p.support(CapabilityId.OWNER_MATCH_V4))
        assertEquals(null, PolicyCompiler.preview(Fx.policy(Rule("w", "p-browser", Action.DENY)), Fx.principals, p, "c", null, 0).plan)
    }
}
