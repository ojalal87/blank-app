package com.rootguard.core

import com.rootguard.core.model.EnforcementHealth
import com.rootguard.core.model.Phase
import com.rootguard.core.protocol.ErrorCode
import com.rootguard.core.protocol.FamilyHealth
import com.rootguard.core.protocol.Health
import com.rootguard.core.protocol.Operation
import com.rootguard.core.protocol.Payload
import com.rootguard.core.protocol.Request
import com.rootguard.core.protocol.RequestGate
import com.rootguard.core.protocol.Response
import com.rootguard.core.protocol.encode
import com.rootguard.core.txn.Decision
import com.rootguard.core.txn.EMPTY_MARK
import com.rootguard.core.txn.Reconciler
import com.rootguard.core.txn.Transitions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private fun req(op: Operation = Operation.HEALTH, payload: Payload = Payload.None, boot: String = "b", exp: Long = 10_000, v: Int = 1, rid: String = id) =
        Request(v, rid, op, boot, exp, null, payload).encode()

    @Test
    fun `envelope checks`() {
        val g = RequestGate("b")
        assertIs<RequestGate.Outcome.Accept>(g.admit(req(), 5_000))
        fun code(raw: String) = ((g.admit(raw, 5_000) as RequestGate.Outcome.Reject).response.error)
        assertEquals(ErrorCode.UNSUPPORTED_PROTOCOL, code(req(v = 2, rid = "223e4567-e89b-12d3-a456-426614174000")))
        assertEquals(ErrorCode.CAPABILITY_STALE, code(req(boot = "other", rid = "323e4567-e89b-12d3-a456-426614174000")))
        assertEquals(ErrorCode.EXPIRED_REQUEST, code(req(exp = 4_000, rid = "423e4567-e89b-12d3-a456-426614174000")))
        assertEquals(ErrorCode.EXPIRED_REQUEST, code(req(exp = 5_000 + 61_000, rid = "523e4567-e89b-12d3-a456-426614174000")))
        assertEquals(ErrorCode.MALFORMED, code(req(rid = "not-a-uuid")))
        assertEquals(ErrorCode.MALFORMED, code(req(op = Operation.APPLY, rid = "623e4567-e89b-12d3-a456-426614174000")))
        assertEquals(ErrorCode.MALFORMED, code("""{"protocolVersion":1,"requestId":"$id","operation":"HEALTH","bootId":"b","expiresElapsedMs":9000,"expectedRevision":null,"payload":{"type":"none"},"cmd":"sh"}"""))
        assertEquals(ErrorCode.MALFORMED, code("""{"protocolVersion":1,"requestId":"$id","operation":"SHELL","bootId":"b","expiresElapsedMs":9000,"expectedRevision":null,"payload":{"type":"none"}}"""))
        assertEquals(ErrorCode.TOO_LARGE, code("x".repeat(256 * 1024 + 1)))
    }

    @Test
    fun `replay returns the recorded outcome`() {
        val g = RequestGate("b")
        assertIs<RequestGate.Outcome.Accept>(g.admit(req(), 1))
        val first = Response(id, true)
        g.complete(id, first)
        assertEquals(first, (g.admit(req(), 2) as RequestGate.Outcome.Reject).response)
    }
}

class ReconcileTest {
    private fun health(vararg fam: Triple<Int, Boolean, String>) = Health("b", fam.map { (f, present, h) -> FamilyHealth(f, present, if (present) 1 else 0, true, 1, h, 0) }, emptyList())
    private val target = mapOf(4 to "t4", 6 to "t6")
    private val prev = mapOf(4 to "p4", 6 to "p6")

    @Test
    fun `crash after kernel change but before commit is completed`() {
        assertEquals(Decision.CommitOpen, Reconciler.decide(Phase.APPLYING, target, prev, prev, health(Triple(4, true, "t4"), Triple(6, true, "t6"))))
    }

    @Test
    fun `crash before kernel change marks rolled back, partial state restores previous`() {
        assertEquals(Decision.MarkOpenRolledBack, Reconciler.decide(Phase.PREPARED, target, prev, prev, health(Triple(4, true, "p4"), Triple(6, true, "p6"))))
        assertEquals(Decision.RestorePrevious, Reconciler.decide(Phase.APPLYING, target, prev, prev, health(Triple(4, true, "t4"), Triple(6, true, "p6"))))
    }

    @Test
    fun `steady states - verified, absent after reboot, drift`() {
        assertEquals(EnforcementHealth.ACTIVE, (Reconciler.decide(null, null, null, prev, health(Triple(4, true, "p4"), Triple(6, true, "p6"))) as Decision.Steady).health)
        val off = Reconciler.decide(null, null, null, prev, health(Triple(4, false, "x"), Triple(6, false, "x"))) as Decision.Steady
        assertEquals(EnforcementHealth.OFF to "KERNEL_RULES_ABSENT", off.health to off.reason)
        assertEquals(EnforcementHealth.DEGRADED, (Reconciler.decide(null, null, null, prev, health(Triple(4, true, "zz"), Triple(6, true, "p6"))) as Decision.Steady).health)
        assertEquals(EnforcementHealth.OFF, (Reconciler.decide(null, null, null, null, health(Triple(4, false, "x"))) as Decision.Steady).health)
        val v4only = mapOf(4 to "p4", 6 to EMPTY_MARK)
        assertEquals(EnforcementHealth.ACTIVE, (Reconciler.decide(null, null, null, v4only, health(Triple(4, true, "p4"))) as Decision.Steady).health)
    }

    @Test
    fun `phase machine is one-way`() {
        assertTrue(Transitions.canMove(Phase.PREPARED, Phase.APPLYING))
        assertTrue(!Transitions.canMove(Phase.COMMITTED, Phase.ROLLED_BACK))
        assertTrue(!Transitions.canMove(Phase.FAILED, Phase.APPLYING))
    }
}
