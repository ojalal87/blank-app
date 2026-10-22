package com.rootguard.core.helper

import com.rootguard.core.backend.Iptables
import com.rootguard.core.model.Capability
import com.rootguard.core.model.CapabilityId
import com.rootguard.core.model.Phase
import com.rootguard.core.model.ProbeResult
import com.rootguard.core.model.Support
import com.rootguard.core.policy.ChainPlan
import com.rootguard.core.policy.Plan
import com.rootguard.core.policy.PolicyCompiler
import com.rootguard.core.protocol.ErrorCode
import com.rootguard.core.protocol.FamilyHealth
import com.rootguard.core.protocol.Health
import com.rootguard.core.protocol.Operation
import com.rootguard.core.protocol.Payload
import com.rootguard.core.protocol.Receipt
import com.rootguard.core.protocol.Request
import com.rootguard.core.protocol.RequestGate
import com.rootguard.core.protocol.Response
import com.rootguard.core.protocol.Result
import com.rootguard.core.protocol.RgProtocol
import com.rootguard.core.protocol.encode
import kotlinx.serialization.Serializable

/** argv execution with optional stdin. Implementations must never use a shell. */
fun interface Exec {
    fun run(argv: List<String>, stdin: String?): ExecResult
}

data class ExecResult(val exitCode: Int, val output: String)

/** Read-only system facts the probe needs (files under /proc and /sys). */
interface SysInfo {
    fun selinuxEnforcing(): Boolean?
    fun netInterfaces(): List<String>
    fun binaryExists(path: String): Boolean
}

@Serializable
data class HelperJournal(
    val revisionId: String?,
    val planHash: String?,
    val transactionId: String?,
    val phase: Phase,
    /** Owned rules per family as read back right before the last switch: the compensation target. */
    val previous: List<ChainPlan>,
    val previousRevisionId: String?,
)

interface JournalStore {
    fun read(): HelperJournal?
    fun write(j: HelperJournal)
}

/**
 * Everything the root process does, as a pure state machine over [Exec]. Typed operations only:
 * PROBE, PREVIEW (validate), APPLY, VERIFY, ROLLBACK, HEALTH, RECOVER_OWN_RULES. It touches only the
 * RG_OUT chain, the RG_PROBE scratch chain and one OUTPUT jump, per family.
 */
class HelperEngine(
    private val exec: Exec,
    private val sys: SysInfo,
    private val journal: JournalStore,
    val bootId: String,
) {
    private val gate = RequestGate(bootId)
    private var wait = true
    private var reject = mutableMapOf(4 to true, 6 to true)
    private var families = listOf(4, 6)
    /** APPLY/ROLLBACK need a probe in this helper session (capabilities can change across reboots/ROM updates). */
    private var probed = false

    @Synchronized
    fun handle(raw: String, nowElapsedMs: Long): String {
        val req = when (val o = gate.admit(raw, nowElapsedMs)) {
            is RequestGate.Outcome.Reject -> return o.response.encode()
            is RequestGate.Outcome.Accept -> o.request
        }
        val resp = try {
            dispatch(req)
        } catch (e: Exception) {
            Response.error(req.requestId, ErrorCode.INTERNAL)
        }
        gate.complete(req.requestId, resp)
        return resp.encode()
    }

    private fun dispatch(req: Request): Response {
        val id = req.requestId
        return when (req.operation) {
            Operation.PROBE -> Response(id, true, result = Result.Probe(probe()))
            Operation.HEALTH -> Response(id, true, result = Result.HealthResult(health()))
            Operation.PREVIEW -> {
                val p = req.payload as Payload.PlanPayload
                if (Iptables.validate(p.plan) != null) Response.error(id, ErrorCode.INVALID_PLAN) else Response(id, true, result = Result.Valid)
            }
            Operation.APPLY, Operation.ROLLBACK -> if (!probed) Response.error(id, ErrorCode.CAPABILITY_STALE) else if (req.operation == Operation.ROLLBACK) {
                receipt(id, rollback((req.payload as Payload.RollbackPayload).transactionId))
            } else {
                val p = req.payload as Payload.PlanPayload
                receipt(id, apply(p.transactionId, p.plan, req.expectedRevision))
            }
            Operation.VERIFY -> {
                val p = req.payload as Payload.VerifyPayload
                val h = health()
                val ok = combinedHash(h) == p.expectedHash
                receipt(id, Receipt(null, p.revisionId, if (ok) p.expectedHash else null, if (ok) Phase.VERIFIED else Phase.FAILED, if (ok) null else ErrorCode.VERIFY_FAILED, health = h))
            }
            Operation.RECOVER_OWN_RULES -> receipt(id, recoverOwnRules())
            Operation.SUBSCRIBE_METADATA -> Response.error(id, ErrorCode.UNSUPPORTED_OPERATION)
        }
    }

    private fun receipt(id: String, r: Receipt) = if (r.errorCode == null) Response(id, true, result = Result.ReceiptResult(r)) else Response(id, false, r.errorCode, Result.ReceiptResult(r))

    // --- probe ---

    fun probe(): ProbeResult {
        val caps = ArrayList<Capability>()
        fun cap(id: CapabilityId, s: Support, why: String) { caps += Capability(id, s, why) }
        cap(CapabilityId.ROOT, Support.SUPPORTED, "helper running as root")
        when (sys.selinuxEnforcing()) {
            true -> cap(CapabilityId.SELINUX_ENFORCING, Support.SUPPORTED, "enforcing; RootGuard never changes it")
            false -> cap(CapabilityId.SELINUX_ENFORCING, Support.UNSUPPORTED, "permissive on this ROM")
            null -> cap(CapabilityId.SELINUX_ENFORCING, Support.UNVERIFIED, "state unreadable")
        }
        wait = exec.run(Iptables.probeWait(4), null).exitCode == 0
        cap(CapabilityId.XTABLES_WAIT, if (wait) Support.SUPPORTED else Support.UNSUPPORTED, if (wait) "-w accepted" else "-w rejected; concurrent netd updates may collide")
        val available = ArrayList<Int>()
        for (f in listOf(4, 6)) {
            val tag = if (f == 4) "V4" else "V6"
            val bins = Iptables.binaries(f)
            val present = sys.binaryExists(bins.iptables) && exec.run(Iptables.version(f), null).exitCode == 0
            cap(CapabilityId.valueOf("IPTABLES_$tag"), if (present) Support.SUPPORTED else Support.UNSUPPORTED, if (present) bins.iptables else "binary missing or failing")
            if (!present) {
                listOf("RESTORE_NOFLUSH_", "OWNER_MATCH_", "REJECT_").forEach { cap(CapabilityId.valueOf(it + tag), Support.UNSUPPORTED, "no ${bins.iptables}") }
                continue
            }
            // Scratch chain only: never hooked, so probe rules cannot affect any traffic.
            exec.run(Iptables.chainFlush(f, wait, Iptables.PROBE_CHAIN), null)
            exec.run(Iptables.chainDelete(f, wait, Iptables.PROBE_CHAIN), null)
            val restoreOk = sys.binaryExists(bins.restore) &&
                exec.run(Iptables.restore(f, wait), "*filter\n:${Iptables.PROBE_CHAIN} - [0:0]\nCOMMIT\n").exitCode == 0
            cap(CapabilityId.valueOf("RESTORE_NOFLUSH_$tag"), if (restoreOk) Support.SUPPORTED else Support.UNSUPPORTED, if (restoreOk) "--noflush chain replace works" else "iptables-restore --noflush failed")
            if (!restoreOk) exec.run(Iptables.chainCreate(f, wait, Iptables.PROBE_CHAIN), null)
            val owner = exec.run(Iptables.probeOwner(f, wait), null).exitCode == 0
            cap(CapabilityId.valueOf("OWNER_MATCH_$tag"), if (owner) Support.SUPPORTED else Support.UNSUPPORTED, if (owner) "xt_owner --uid-owner accepted" else "owner match rejected by kernel")
            val rej = exec.run(Iptables.probeReject(f, wait), null).exitCode == 0
            reject[f] = rej
            cap(CapabilityId.valueOf("REJECT_$tag"), if (rej) Support.SUPPORTED else Support.UNSUPPORTED, if (rej) "REJECT target available" else "falls back to DROP")
            exec.run(Iptables.chainFlush(f, wait, Iptables.PROBE_CHAIN), null)
            exec.run(Iptables.chainDelete(f, wait, Iptables.PROBE_CHAIN), null)
            if (restoreOk && owner) available += f
        }
        families = available.ifEmpty { listOf(4) }
        val ifaces = sys.netInterfaces()
        val wifi = if (ifaces.any { it.startsWith("wlan") }) listOf("wlan+") else emptyList()
        val mobile = MOBILE_PREFIXES.filter { p -> ifaces.any { it.startsWith(p) } }.map { "$it+" }
        cap(CapabilityId.WIFI_INTERFACES, if (wifi.isEmpty()) Support.UNSUPPORTED else Support.SUPPORTED, if (wifi.isEmpty()) "no wlan* interface present" else wifi.joinToString())
        cap(CapabilityId.MOBILE_INTERFACES, if (mobile.isEmpty()) Support.UNSUPPORTED else Support.SUPPORTED, if (mobile.isEmpty()) "no known cellular interface present" else mobile.joinToString())
        cap(CapabilityId.VPN_PATH, Support.UNVERIFIED, "owner match on tun+ not yet verified on this device")
        cap(CapabilityId.LAN_SCOPE, Support.SUPPORTED, "private/link-local destination ranges")
        cap(CapabilityId.TETHER_FORWARD, Support.UNSUPPORTED, "forwarded tethering traffic is not filtered in v1")
        cap(CapabilityId.DOMAIN_RULES, Support.UNSUPPORTED, "no resolver mapping in v1; encrypted DNS not visible")
        cap(CapabilityId.NFTABLES, Support.UNVERIFIED, "not used in v1")
        cap(CapabilityId.PER_FLOW_EBPF, Support.UNSUPPORTED, "Android eBPF accounting does not expose per-flow programs")
        cap(CapabilityId.FLOW_METADATA, Support.UNSUPPORTED, "metadata sensor is milestone 2")
        val foreign = listOf(4, 6).filter { it in available }.flatMap { readBack(it).foreignChains }.distinct()
        cap(CapabilityId.NO_CONFLICTING_FIREWALL, if (foreign.isEmpty()) Support.SUPPORTED else Support.UNSUPPORTED, if (foreign.isEmpty()) "no other firewall chains found" else foreign.joinToString())
        probed = true
        return ProbeResult(bootId, PROBE_VERSION, System.currentTimeMillis(), caps, wifi, mobile, foreign)
    }

    // --- read-back ---

    private fun readBack(f: Int): Iptables.ReadBack {
        val r = exec.run(Iptables.save(f), null)
        return Iptables.parseSave(f, if (r.exitCode == 0) r.output else "")
    }

    fun health(): Health {
        val rbs = listOf(4, 6).map(::readBack)
        return Health(bootId, rbs.map { FamilyHealth(it.family, it.chainPresent, it.hookCount, it.hookFirst, it.rules.size, it.hash, it.unparsed.size) }, rbs.flatMap { it.foreignChains }.distinct())
    }

    /** Hash over the families that currently have an owned chain, the same way the compiler hashes a plan. */
    private fun combinedHash(h: Health): String {
        val chains = listOf(4, 6).mapNotNull { f -> readBack(f).takeIf { it.chainPresent }?.let { ChainPlan(f, it.rules) } }
        return PolicyCompiler.hash(chains)
    }

    // --- apply / compensation ---

    private fun apply(txId: String, plan: Plan, expected: String?): Receipt {
        if (Iptables.validate(plan) != null) return Receipt(txId, plan.revisionId, null, Phase.FAILED, ErrorCode.INVALID_PLAN)
        val j = journal.read()
        if (j != null && j.revisionId != expected) return Receipt(txId, plan.revisionId, null, Phase.FAILED, ErrorCode.REVISION_CONFLICT)
        if (plan.chains.any { it.family !in families }) return Receipt(txId, plan.revisionId, null, Phase.FAILED, ErrorCode.UNSUPPORTED_BACKEND)
        val before = listOf(4, 6).associateWith(::readBack)
        if (before.values.any { it.unparsed.isNotEmpty() }) return Receipt(txId, plan.revisionId, null, Phase.FAILED, ErrorCode.VERIFY_FAILED, health = health())
        val previous = before.values.filter { it.chainPresent || it.hookCount > 0 }.map { ChainPlan(it.family, it.rules) }
        // Recovery state is persisted before anything is switched.
        journal.write(HelperJournal(j?.revisionId, j?.planHash, txId, Phase.APPLYING, previous, j?.revisionId))
        val touched = ArrayList<Int>()
        for (f in listOf(4, 6)) {
            val target = plan.chains.firstOrNull { it.family == f }
            touched += f
            val ok = if (target != null) install(target) else removeOwned(f)
            if (!ok) return compensate(txId, plan.revisionId, touched, before, j)
        }
        val h = health()
        val hash = combinedHash(h)
        if (hash != plan.canonicalHash || h.families.any { it.hookCount > 1 || it.unparsedCount > 0 }) {
            return compensate(txId, plan.revisionId, touched, before, j)
        }
        journal.write(HelperJournal(plan.revisionId, plan.canonicalHash, txId, Phase.VERIFIED, previous, j?.revisionId))
        return Receipt(txId, plan.revisionId, hash, Phase.VERIFIED, health = h)
    }

    private fun install(chain: ChainPlan): Boolean {
        val f = chain.family
        if (exec.run(Iptables.restore(f, wait), Iptables.restoreInput(chain, reject[f] != false)).exitCode != 0) return false
        if (exec.run(Iptables.hookCheck(f, wait), null).exitCode != 0) {
            if (exec.run(Iptables.hookInsert(f, wait), null).exitCode != 0) return false
        }
        return true
    }

    /** Removes only RG-owned state for one family: every OUTPUT jump to RG_OUT, then the chain. */
    private fun removeOwned(f: Int): Boolean {
        if (!sys.binaryExists(Iptables.binaries(f).iptables)) return true
        var guard = 0
        while (exec.run(Iptables.hookCheck(f, wait), null).exitCode == 0 && guard++ < 16) {
            if (exec.run(Iptables.hookDelete(f, wait), null).exitCode != 0) return false
        }
        exec.run(Iptables.chainFlush(f, wait), null)
        exec.run(Iptables.chainDelete(f, wait), null)
        return !readBack(f).chainPresent && readBack(f).hookCount == 0
    }

    private fun restoreFamily(f: Int, rb: Iptables.ReadBack): Boolean {
        val now = readBack(f)
        // An atomic restore that failed changed nothing; only undo what actually differs.
        if (now.chainPresent == rb.chainPresent && now.hookCount == rb.hookCount && now.hash == rb.hash) return true
        if (!rb.chainPresent && rb.hookCount == 0) return removeOwned(f)
        if (!install(ChainPlan(f, rb.rules))) return false
        if (rb.hookCount == 0) exec.run(Iptables.hookDelete(f, wait), null)
        return readBack(f).hash == rb.hash
    }

    private fun compensate(txId: String, revisionId: String, touched: List<Int>, before: Map<Int, Iptables.ReadBack>, j: HelperJournal?): Receipt {
        val restored = touched.all { restoreFamily(it, before.getValue(it)) }
        val h = health()
        return if (restored) {
            journal.write(HelperJournal(j?.revisionId, j?.planHash, txId, Phase.ROLLED_BACK, emptyList(), j?.previousRevisionId))
            Receipt(txId, revisionId, null, Phase.ROLLED_BACK, ErrorCode.VERIFY_FAILED, compensated = true, health = h)
        } else {
            journal.write(HelperJournal(null, null, txId, Phase.FAILED, emptyList(), j?.revisionId))
            Receipt(txId, revisionId, null, Phase.FAILED, ErrorCode.ROLLBACK_FAILED, health = h)
        }
    }

    private fun rollback(txId: String): Receipt {
        val j = journal.read() ?: return Receipt(txId, null, null, Phase.FAILED, ErrorCode.ROLLBACK_FAILED)
        if (j.transactionId != txId) return Receipt(txId, null, null, Phase.FAILED, ErrorCode.REVISION_CONFLICT)
        val ok = listOf(4, 6).all { f ->
            val prev = j.previous.firstOrNull { it.family == f }
            if (prev == null) removeOwned(f) else install(prev) && readBack(f).hash == PolicyCompiler.familyHash(f, prev.rules)
        }
        val h = health()
        return if (ok) {
            journal.write(HelperJournal(j.previousRevisionId, null, txId, Phase.ROLLED_BACK, emptyList(), null))
            Receipt(txId, j.previousRevisionId, combinedHash(h), Phase.ROLLED_BACK, health = h)
        } else {
            Receipt(txId, null, null, Phase.FAILED, ErrorCode.ROLLBACK_FAILED, health = h)
        }
    }

    /** Local recovery: removes RG-owned hooks and chains only. Android's and other apps' rules are untouched. */
    private fun recoverOwnRules(): Receipt {
        val ok = listOf(4, 6).all(::removeOwned)
        val h = health()
        journal.write(HelperJournal(null, null, null, if (ok) Phase.COMMITTED else Phase.FAILED, emptyList(), null))
        return Receipt(null, null, null, if (ok) Phase.COMMITTED else Phase.FAILED, if (ok) null else ErrorCode.ROLLBACK_FAILED, health = h)
    }

    companion object {
        const val PROBE_VERSION = "rg-probe-1"
        val MOBILE_PREFIXES = listOf("rmnet_data", "rmnet", "ccmni", "r_rmnet_data", "seth_lte", "rmnet_ipa", "clat")
        fun handshake(bootId: String) = """{"protocolVersion":${RgProtocol.VERSION},"bootId":"${bootId.filter { it.isLetterOrDigit() || it == '-' }.take(64)}"}"""
    }
}
