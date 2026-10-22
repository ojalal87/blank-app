package com.rootguard.core.txn

import com.rootguard.core.model.EnforcementHealth
import com.rootguard.core.model.Phase
import com.rootguard.core.policy.ChainPlan
import com.rootguard.core.policy.PolicyCompiler
import com.rootguard.core.protocol.Health

object Transitions {
    private val allowed = mapOf(
        Phase.PREPARED to setOf(Phase.APPLYING, Phase.ROLLED_BACK, Phase.FAILED),
        Phase.APPLYING to setOf(Phase.VERIFIED, Phase.ROLLED_BACK, Phase.FAILED),
        Phase.VERIFIED to setOf(Phase.COMMITTED, Phase.ROLLED_BACK, Phase.FAILED),
        Phase.COMMITTED to emptySet(),
        Phase.ROLLED_BACK to emptySet(),
        Phase.FAILED to emptySet(),
    )
    fun canMove(from: Phase, to: Phase): Boolean = to in allowed.getValue(from)
    fun isOpen(p: Phase): Boolean = p == Phase.PREPARED || p == Phase.APPLYING || p == Phase.VERIFIED
}

/** Per-family expected hashes of a plan; families absent from the plan are expected to be empty. */
fun expectedFamilyHashes(chains: List<ChainPlan>): Map<Int, String> =
    listOf(4, 6).associateWith { f -> chains.firstOrNull { it.family == f }?.let { PolicyCompiler.familyHash(f, it.rules) } ?: EMPTY_MARK }

const val EMPTY_MARK = "empty"

fun Health.matches(expected: Map<Int, String>): Boolean = expected.all { (f, h) ->
    val fh = families.firstOrNull { it.family == f }
    if (h == EMPTY_MARK) fh == null || (!fh.chainPresent && fh.hookCount == 0)
    else fh != null && fh.chainPresent && fh.hookCount == 1 && fh.unparsedCount == 0 && fh.hash == h
}

sealed interface Decision {
    /** Kernel equals the target of the open transaction: finish it. */
    data object CommitOpen : Decision
    /** Kernel equals the previous verified plan: the open transaction never took effect. */
    data object MarkOpenRolledBack : Decision
    /** Kernel matches neither: restore the previous verified plan (or remove own rules if there was none). */
    data object RestorePrevious : Decision
    data class Steady(val health: EnforcementHealth, val reason: String) : Decision
}

/**
 * Deterministic crash/boot reconciliation between the DB journal and the helper's kernel read-back.
 * The DB commit is never assumed atomic with the kernel change.
 */
object Reconciler {
    fun decide(openTx: Phase?, target: Map<Int, String>?, previous: Map<Int, String>?, applied: Map<Int, String>?, health: Health): Decision {
        val prev = previous ?: mapOf(4 to EMPTY_MARK, 6 to EMPTY_MARK)
        if (openTx != null && Transitions.isOpen(openTx) && target != null) {
            return when {
                health.matches(target) -> Decision.CommitOpen
                health.matches(prev) -> Decision.MarkOpenRolledBack
                else -> Decision.RestorePrevious
            }
        }
        if (applied == null) {
            return if (!health.anyOwned) Decision.Steady(EnforcementHealth.OFF, "NO_POLICY") else Decision.Steady(EnforcementHealth.DEGRADED, "UNEXPECTED_OWNED_RULES")
        }
        return when {
            health.matches(applied) -> Decision.Steady(EnforcementHealth.ACTIVE, if (health.families.any { !it.hookFirst && it.hookCount == 1 }) "HOOK_NOT_FIRST" else "VERIFIED")
            !health.anyOwned -> Decision.Steady(EnforcementHealth.OFF, "KERNEL_RULES_ABSENT")
            else -> Decision.Steady(EnforcementHealth.DEGRADED, "DRIFT")
        }
    }
}
