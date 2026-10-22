package com.bright.onepluscare.core.dns

/**
 * Exact-host blocklist built from a hosts-format list (e.g. StevenBlack/hosts, MIT). Stored as a sorted
 * array of 64-bit hashes (about 8 bytes per host instead of a String each). Two independent hashes are
 * combined; the chance of a wrong match is negligible for lists of this size (~1e-9 for 200k entries).
 */
class Blocklist private constructor(private val hashes: LongArray) {
    val size: Int get() = hashes.size

    fun contains(host: String): Boolean = java.util.Arrays.binarySearch(hashes, hash(host)) >= 0

    companion object {
        const val MAX_BYTES = 12 * 1024 * 1024
        const val MIN_ENTRIES = 1000
        val EMPTY = Blocklist(LongArray(0))

        private val HOST = Regex("^[a-z0-9_]([a-z0-9_-]{0,62}\\.)+[a-z0-9-]{2,63}$")
        private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")

        fun hash(host: String): Long {
            var h1 = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
            var h2 = 0x84222325cbf29ce4uL.toLong()
            for (ch in host) {
                h1 = (h1 xor ch.code.toLong()) * 0x100000001b3L
                h2 = (h2 + ch.code.toLong()) * -0x61c8864680b583ebL
                h2 = h2 xor (h2 ushr 29)
            }
            return h1 xor (h2 * 0x2545F4914F6CDD1DL)
        }

        /** Accepts "0.0.0.0 host", "127.0.0.1 host", "::1 host" and bare "host" lines; comments ignored. */
        fun parse(text: String): Blocklist {
            if (text.length > MAX_BYTES) return EMPTY
            val out = ArrayList<Long>(200_000)
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                val names = when {
                    parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1") -> parts.drop(1)
                    parts.size == 1 -> parts
                    else -> emptyList()
                }
                for (n in names) {
                    val host = n.lowercase().trimEnd('.')
                    if (host in SKIP || !HOST.matches(host)) continue
                    out += hash(host)
                }
            }
            val arr = out.toLongArray()
            arr.sort()
            return Blocklist(arr.distinct())
        }

        private fun LongArray.distinct(): LongArray {
            if (isEmpty()) return this
            var w = 1
            for (r in 1 until size) if (this[r] != this[w - 1]) this[w++] = this[r]
            return copyOf(w)
        }
    }
}

enum class DnsVerdict { ALLOW, BLOCK, ALLOWED_BY_EXCEPTION }

/** Exceptions are suffix rules ("example.com" also allows "cdn.example.com"); the list itself is exact. */
class DnsPolicy(private val list: Blocklist, exceptions: Collection<String>) {
    private val allow = exceptions.map { it.lowercase().trimEnd('.') }.filter { it.isNotEmpty() }.toSet()

    fun decide(host: String): DnsVerdict {
        val h = host.lowercase().trimEnd('.')
        if (!list.contains(h)) return DnsVerdict.ALLOW
        var s = h
        while (true) {
            if (s in allow) return DnsVerdict.ALLOWED_BY_EXCEPTION
            val dot = s.indexOf('.')
            if (dot < 0) break
            s = s.substring(dot + 1)
        }
        return DnsVerdict.BLOCK
    }
}
