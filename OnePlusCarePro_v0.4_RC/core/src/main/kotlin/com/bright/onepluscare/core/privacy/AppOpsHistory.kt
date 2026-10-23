package com.bright.onepluscare.core.privacy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Sensitive accesses the app reports on. Values are the AppOps names `dumpsys appops --op` accepts. */
enum class PrivacyOp(val opName: String) {
    CAMERA("CAMERA"),
    MICROPHONE("RECORD_AUDIO"),
    FINE_LOCATION("FINE_LOCATION"),
    COARSE_LOCATION("COARSE_LOCATION");

    companion object {
        fun byOpName(n: String): PrivacyOp? = entries.firstOrNull { it.opName == n }
    }
}

/** Where the app was when it used the op. Android records this as a uid state tag ("top-s", "bg-tp", ...). */
enum class AccessState { FOREGROUND, FOREGROUND_SERVICE, BACKGROUND, UNKNOWN }

@Serializable
data class OpAccess(
    val packageName: String,
    val op: PrivacyOp,
    /** Milliseconds between the most recent recorded access and the dump. */
    val agoMs: Long,
    val state: AccessState,
    /** Access lines Android kept for this package/op (it keeps only a few per state). */
    val records: Int,
    val anyBackground: Boolean,
)

/**
 * Parser for `dumpsys appops --op <OP>` output (Android 10 "time=+..ago" form and Android 11+
 * "Access: [state] date (-relative)" form, with or without attribution tags). Lines it does not
 * recognise are ignored; nothing is guessed. Runs inside the Shizuku process so only this compact,
 * typed summary crosses the Binder.
 */
object AppOpsHistory {
    private val PACKAGE = Regex("^\\s*Package\\s+([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+):\\s*$")
    private val OP = Regex("^\\s*([A-Z_]+)\\s*\\(")
    private val ACCESS = Regex("Access:\\s*\\[([a-z]+)[^\\]]*\\][^(]*\\((-[0-9dhms]+)\\)")
    private val LEGACY = Regex("time=\\+([0-9dhms]+)\\s+ago")
    private val json = Json { ignoreUnknownKeys = false }

    fun parseDuration(s: String): Long? {
        val t = s.trim().removePrefix("-").removePrefix("+")
        if (t.isEmpty()) return null
        var total = 0L
        var i = 0
        while (i < t.length) {
            val start = i
            while (i < t.length && t[i].isDigit()) i++
            if (start == i) return null
            val n = t.substring(start, i).toLong()
            val unit = when {
                t.startsWith("ms", i) -> { i += 2; 1L }
                i < t.length && t[i] == 'd' -> { i++; 86_400_000L }
                i < t.length && t[i] == 'h' -> { i++; 3_600_000L }
                i < t.length && t[i] == 'm' -> { i++; 60_000L }
                i < t.length && t[i] == 's' -> { i++; 1_000L }
                else -> return null
            }
            total += n * unit
        }
        return total
    }

    private fun state(tag: String): AccessState = when {
        tag.startsWith("top") || tag.startsWith("pers") -> AccessState.FOREGROUND
        tag.startsWith("fgsvc") || tag.startsWith("fgs") -> AccessState.FOREGROUND_SERVICE
        tag.startsWith("fg") -> AccessState.FOREGROUND
        tag.startsWith("bg") || tag.startsWith("cch") -> AccessState.BACKGROUND
        else -> AccessState.UNKNOWN
    }

    private class Acc(var ago: Long, var state: AccessState, var n: Int = 0, var bg: Boolean = false) {
        fun add(ago: Long, st: AccessState) {
            n++
            if (st == AccessState.BACKGROUND) bg = true
            if (ago < this.ago) { this.ago = ago; state = st }
        }
    }

    fun parse(dump: String, wanted: PrivacyOp): List<OpAccess> {
        val out = LinkedHashMap<String, Acc>()
        var pkg: String? = null
        var inOp = false
        fun record(ago: Long?, st: AccessState) {
            val p = pkg ?: return
            if (ago == null || ago < 0) return
            out.getOrPut(p) { Acc(ago, st) }.add(ago, st)
        }
        for (line in dump.lineSequence()) {
            val pm = PACKAGE.find(line)
            if (pm != null) { pkg = pm.groupValues[1]; inOp = false; continue }
            if (line.trimStart().startsWith("Uid ")) { pkg = null; inOp = false; continue }
            val opName = OP.find(line)?.groupValues?.get(1)
            if (opName != null && !line.contains("Access:") && !line.contains("Reject:")) {
                inOp = opName == wanted.opName
                if (inOp) LEGACY.find(line)?.let { m -> record(parseDuration(m.groupValues[1]), AccessState.UNKNOWN) }
                continue
            }
            if (!inOp) continue
            val m = ACCESS.find(line) ?: continue
            record(parseDuration(m.groupValues[2]), state(m.groupValues[1]))
        }
        return out.map { (p, a) -> OpAccess(p, wanted, a.ago, a.state, a.n, a.bg) }.sortedBy { it.agoMs }
    }

    fun encode(list: List<OpAccess>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(OpAccess.serializer()), list)
    fun decode(s: String): List<OpAccess>? = runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(OpAccess.serializer()), s) }.getOrNull()
}
