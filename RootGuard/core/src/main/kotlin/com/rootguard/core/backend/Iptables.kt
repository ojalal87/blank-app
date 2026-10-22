package com.rootguard.core.backend

import com.rootguard.core.model.Protocol
import com.rootguard.core.net.Cidr
import com.rootguard.core.policy.ChainPlan
import com.rootguard.core.policy.CompiledRule
import com.rootguard.core.policy.Plan
import com.rootguard.core.policy.PolicyCompiler
import com.rootguard.core.policy.Verdict

/**
 * iptables/ip6tables backend. Owns exactly one chain per family ([CHAIN]) and one jump from filter/OUTPUT.
 * It never flushes or edits built-in chains or netd-owned chains. The chain is replaced in a single
 * `iptables-restore --noflush` commit (declaring an existing user chain flushes only that chain).
 * Everything rendered here comes from validated typed values; nothing is passed through a shell.
 */
object Iptables {
    const val CHAIN = "RG_OUT"
    const val PROBE_CHAIN = "RG_PROBE"
    const val MAX_RULES_PER_CHAIN = 4000
    private val IFACE = Regex("^[A-Za-z0-9_.-]{1,15}\\+?$")

    data class Binaries(val iptables: String, val restore: String, val save: String)

    val V4 = Binaries("/system/bin/iptables", "/system/bin/iptables-restore", "/system/bin/iptables-save")
    val V6 = Binaries("/system/bin/ip6tables", "/system/bin/ip6tables-restore", "/system/bin/ip6tables-save")
    fun binaries(family: Int): Binaries = if (family == 4) V4 else V6

    enum class PlanError { TOO_MANY_RULES, BAD_FAMILY, BAD_UID, BAD_INTERFACE, BAD_DESTINATION, BAD_PORTS, BAD_PROTOCOL, HASH_MISMATCH, DUPLICATE_FAMILY, BAD_ID }

    /** Helper-side re-validation of a plan received over IPC. Never trusts the app's compiler. */
    fun validate(plan: Plan): PlanError? {
        if (!com.rootguard.core.model.Ids.isSafeId(plan.revisionId) || !Regex("^[0-9a-f]{64}$").matches(plan.canonicalHash)) return PlanError.BAD_ID
        if (plan.chains.map { it.family }.toSet().size != plan.chains.size) return PlanError.DUPLICATE_FAMILY
        for (c in plan.chains) {
            if (c.family != 4 && c.family != 6) return PlanError.BAD_FAMILY
            if (c.rules.size > MAX_RULES_PER_CHAIN) return PlanError.TOO_MANY_RULES
            for (r in c.rules) validateRule(c.family, r)?.let { return it }
        }
        if (PolicyCompiler.hash(plan.chains) != plan.canonicalHash) return PlanError.HASH_MISMATCH
        return null
    }

    private fun validateRule(family: Int, r: CompiledRule): PlanError? {
        if (r.family != family) return PlanError.BAD_FAMILY
        if ((r.uidStart == null) != (r.uidEnd == null)) return PlanError.BAD_UID
        if (r.uidStart != null && (r.uidStart < 0 || r.uidEnd!! < r.uidStart)) return PlanError.BAD_UID
        if (r.outInterface != null && !IFACE.matches(r.outInterface)) return PlanError.BAD_INTERFACE
        if (r.destination != null) {
            val c = Cidr.parse(r.destination) ?: return PlanError.BAD_DESTINATION
            if (c.family != family || c.toString() != r.destination) return PlanError.BAD_DESTINATION
        }
        val ports = r.portFrom != null || r.portTo != null
        if (ports && (r.protocol != Protocol.TCP && r.protocol != Protocol.UDP)) return PlanError.BAD_PORTS
        if (ports && (r.portFrom == null || r.portTo == null || r.portFrom !in 1..65535 || r.portTo !in 1..65535 || r.portFrom > r.portTo)) return PlanError.BAD_PORTS
        if ((r.protocol == Protocol.ICMP && family != 4) || (r.protocol == Protocol.ICMPV6 && family != 6)) return PlanError.BAD_PROTOCOL
        if (!com.rootguard.core.model.Ids.isSafeId(r.sourceRuleId)) return PlanError.BAD_ID
        return null
    }

    /** iptables-restore input replacing only [CHAIN] in the filter table. */
    fun restoreInput(chain: ChainPlan, rejectSupported: Boolean): String = buildString {
        append("*filter\n")
        append(":$CHAIN - [0:0]\n")
        chain.rules.forEach { append("-A $CHAIN ").append(ruleArgs(chain.family, it, rejectSupported).joinToString(" ")).append('\n') }
        append("COMMIT\n")
    }

    fun ruleArgs(family: Int, r: CompiledRule, rejectSupported: Boolean): List<String> {
        val a = ArrayList<String>()
        r.outInterface?.let { a += listOf("-o", it) }
        r.destination?.let { a += listOf("-d", it) }
        when (r.protocol) {
            Protocol.ANY -> Unit
            Protocol.TCP -> a += listOf("-p", "tcp")
            Protocol.UDP -> a += listOf("-p", "udp")
            Protocol.ICMP -> a += listOf("-p", "icmp")
            Protocol.ICMPV6 -> a += listOf("-p", "ipv6-icmp")
        }
        if (r.uidStart != null) a += listOf("-m", "owner", "--uid-owner", if (r.uidStart == r.uidEnd) "${r.uidStart}" else "${r.uidStart}-${r.uidEnd}")
        if (r.portFrom != null) a += listOf("-m", r.protocol.name.lowercase(), "--dport", if (r.portFrom == r.portTo) "${r.portFrom}" else "${r.portFrom}:${r.portTo}")
        when (r.verdict) {
            Verdict.RETURN -> a += listOf("-j", "RETURN")
            Verdict.DENY -> a += when {
                !rejectSupported -> listOf("-j", "DROP")
                r.protocol == Protocol.TCP -> listOf("-j", "REJECT", "--reject-with", "tcp-reset")
                family == 4 -> listOf("-j", "REJECT", "--reject-with", "icmp-port-unreachable")
                else -> listOf("-j", "REJECT", "--reject-with", "icmp6-port-unreachable")
            }
        }
        return a
    }

    // --- argv for single operations (helper executes these with ProcessBuilder, no shell) ---
    fun hookCheck(family: Int, wait: Boolean) = base(family, wait) + listOf("-t", "filter", "-C", "OUTPUT", "-j", CHAIN)
    fun hookInsert(family: Int, wait: Boolean) = base(family, wait) + listOf("-t", "filter", "-I", "OUTPUT", "1", "-j", CHAIN)
    fun hookDelete(family: Int, wait: Boolean) = base(family, wait) + listOf("-t", "filter", "-D", "OUTPUT", "-j", CHAIN)
    fun chainFlush(family: Int, wait: Boolean, chain: String = CHAIN) = base(family, wait) + listOf("-t", "filter", "-F", chain)
    fun chainDelete(family: Int, wait: Boolean, chain: String = CHAIN) = base(family, wait) + listOf("-t", "filter", "-X", chain)
    fun chainCreate(family: Int, wait: Boolean, chain: String) = base(family, wait) + listOf("-t", "filter", "-N", chain)
    fun restore(family: Int, wait: Boolean) = listOf(binaries(family).restore) + (if (wait) listOf("-w") else emptyList()) + listOf("--noflush")
    fun save(family: Int) = listOf(binaries(family).save, "-t", "filter")
    fun version(family: Int) = listOf(binaries(family).iptables, "-V")

    /** Probe rules go into an unhooked scratch chain, so they can never affect traffic. */
    fun probeOwner(family: Int, wait: Boolean) = base(family, wait) + listOf("-t", "filter", "-A", PROBE_CHAIN, "-m", "owner", "--uid-owner", "99999", "-j", "RETURN")
    fun probeReject(family: Int, wait: Boolean) = base(family, wait) + listOf("-t", "filter", "-A", PROBE_CHAIN, "-j", "REJECT")
    fun probeWait(family: Int) = listOf(binaries(family).iptables, "-w", "-t", "filter", "-S", "OUTPUT")

    private fun base(family: Int, wait: Boolean) = listOf(binaries(family).iptables) + if (wait) listOf("-w") else emptyList()

    // --- read-back ---

    data class ReadBack(
        val family: Int,
        val chainPresent: Boolean,
        val hookCount: Int,
        /** Hook is the first rule of OUTPUT. Other managers may insert ahead of it later. */
        val hookFirst: Boolean,
        val rules: List<CompiledRule>,
        val unparsed: List<String>,
        val foreignChains: List<String>,
    ) {
        val hash: String get() = PolicyCompiler.familyHash(family, rules)
        val empty: Boolean get() = !chainPresent && hookCount == 0
    }

    private val KNOWN_FOREIGN = listOf("afwall", "droidwall", "netguard", "nm_", "rh_", "pd_")

    /** Parses `iptables-save -t filter` output. Lines this parser does not understand are reported, never guessed. */
    fun parseSave(family: Int, text: String): ReadBack {
        var chainPresent = false
        var hookCount = 0
        var firstOutputRule: String? = null
        val rules = ArrayList<CompiledRule>()
        val unparsed = ArrayList<String>()
        val chains = ArrayList<String>()
        var inFilter = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("*")) { inFilter = line == "*filter"; continue }
            if (!inFilter || line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith(":")) {
                val name = line.substring(1).substringBefore(' ')
                chains += name
                if (name == CHAIN) chainPresent = true
                continue
            }
            if (line.startsWith("-A OUTPUT ")) {
                if (firstOutputRule == null) firstOutputRule = line
                if (line == "-A OUTPUT -j $CHAIN") hookCount++
                continue
            }
            if (line.startsWith("-A $CHAIN ")) {
                val r = parseRule(family, line.removePrefix("-A $CHAIN ").split(' ').filter { it.isNotEmpty() })
                if (r == null) unparsed += line.take(200) else rules += r
            }
        }
        val foreign = chains.filter { c -> KNOWN_FOREIGN.any { c.lowercase().startsWith(it) } }
        return ReadBack(family, chainPresent, hookCount, firstOutputRule == "-A OUTPUT -j $CHAIN", rules, unparsed, foreign)
    }

    private fun parseRule(family: Int, t: List<String>): CompiledRule? {
        var i = 0
        var out: String? = null
        var dest: String? = null
        var proto = Protocol.ANY
        var uidS: Int? = null
        var uidE: Int? = null
        var pf: Int? = null
        var pt: Int? = null
        var verdict: Verdict? = null
        fun next(): String? = t.getOrNull(++i)
        while (i < t.size) {
            when (t[i]) {
                "-o" -> out = next() ?: return null
                "-d" -> dest = next()?.let { Cidr.parse(it)?.toString() } ?: return null
                "-p" -> proto = when (next()) {
                    "tcp" -> Protocol.TCP
                    "udp" -> Protocol.UDP
                    "icmp" -> Protocol.ICMP
                    "ipv6-icmp", "icmpv6" -> Protocol.ICMPV6
                    else -> return null
                }
                "-m" -> when (next()) {
                    "owner", "tcp", "udp" -> Unit
                    else -> return null
                }
                "--uid-owner" -> {
                    val v = next() ?: return null
                    val parts = v.split('-')
                    uidS = parts[0].toIntOrNull() ?: return null
                    uidE = (if (parts.size == 2) parts[1].toIntOrNull() else uidS) ?: return null
                    if (parts.size > 2) return null
                }
                "--dport" -> {
                    val v = next() ?: return null
                    val parts = v.split(':')
                    pf = parts[0].toIntOrNull() ?: return null
                    pt = (if (parts.size == 2) parts[1].toIntOrNull() else pf) ?: return null
                    if (parts.size > 2) return null
                }
                "-j" -> verdict = when (next()) {
                    "RETURN" -> Verdict.RETURN
                    "REJECT", "DROP" -> Verdict.DENY
                    else -> return null
                }
                "--reject-with" -> next() ?: return null
                else -> return null
            }
            i++
        }
        return CompiledRule(family, uidS, uidE, out, dest, proto, pf, pt, verdict ?: return null, "readback")
    }
}
