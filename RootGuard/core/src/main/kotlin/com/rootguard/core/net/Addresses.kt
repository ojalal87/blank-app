package com.rootguard.core.net

import java.net.IDN

/**
 * Canonical IP/CIDR handling without java.net.InetAddress (which may perform DNS lookups for host names).
 * Addresses are 4 or 16 bytes; CIDR network addresses must have host bits cleared.
 */
class IpAddress private constructor(val bytes: ByteArray) {
    val family: Int get() = if (bytes.size == 4) 4 else 6
    override fun equals(other: Any?): Boolean = other is IpAddress && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = if (family == 4) bytes.joinToString(".") { (it.toInt() and 0xff).toString() } else formatV6(bytes)

    companion object {
        fun of(bytes: ByteArray): IpAddress? = if (bytes.size == 4 || bytes.size == 16) IpAddress(bytes.copyOf()) else null

        fun parse(text: String): IpAddress? {
            if (text.isEmpty() || text.length > 45) return null
            return if (':' in text) parseV6(text)?.let(::IpAddress) else parseV4(text)?.let(::IpAddress)
        }

        private fun parseV4(s: String): ByteArray? {
            val parts = s.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
                if (p.length > 1 && p[0] == '0') return null // ambiguous octal-looking form
                val v = p.toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }

        private fun parseV6(input: String): ByteArray? {
            if ('%' in input) return null // zone IDs are not routable destinations
            var s = input
            val lastColon = s.lastIndexOf(':')
            var v4: ByteArray? = null
            if ('.' in s) {
                v4 = parseV4(s.substring(lastColon + 1)) ?: return null
                s = s.substring(0, lastColon + 1) + "0:0" // placeholder words, replaced below
            }
            val parts = s.split("::")
            if (parts.size > 2) return null
            fun words(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                return part.split(':').map { g ->
                    if (g.isEmpty() || g.length > 4 || !g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                    g.toInt(16)
                }
            }
            val head = words(parts[0]) ?: return null
            val tail = if (parts.size == 2) words(parts[1]) ?: return null else emptyList()
            val all: List<Int> = if (parts.size == 2) {
                if (head.size + tail.size > 7) return null
                head + List(8 - head.size - tail.size) { 0 } + tail
            } else {
                if (head.size != 8) return null
                head
            }
            val out = ByteArray(16)
            all.forEachIndexed { i, w -> out[i * 2] = (w shr 8).toByte(); out[i * 2 + 1] = w.toByte() }
            if (v4 != null) v4.copyInto(out, 12)
            return out
        }

        /** RFC 5952 text form. */
        private fun formatV6(b: ByteArray): String {
            val w = IntArray(8) { ((b[it * 2].toInt() and 0xff) shl 8) or (b[it * 2 + 1].toInt() and 0xff) }
            var bestStart = -1; var bestLen = 0; var i = 0
            while (i < 8) {
                if (w[i] == 0) {
                    var j = i
                    while (j < 8 && w[j] == 0) j++
                    if (j - i > bestLen && j - i >= 2) { bestStart = i; bestLen = j - i }
                    i = j
                } else i++
            }
            val sb = StringBuilder()
            i = 0
            while (i < 8) {
                if (i == bestStart) {
                    sb.append("::"); i += bestLen; continue
                }
                if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
                sb.append(Integer.toHexString(w[i]))
                i++
            }
            return sb.toString()
        }
    }
}

data class Cidr(val network: IpAddress, val prefix: Int) {
    val family: Int get() = network.family
    override fun toString(): String = "$network/$prefix"

    fun contains(ip: IpAddress): Boolean {
        if (ip.family != family) return false
        var bits = prefix
        for (i in ip.bytes.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
            if ((ip.bytes[i].toInt() and mask) != (network.bytes[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    companion object {
        /**
         * Parses "addr/len" or a bare address (full-length prefix). With [strict], host bits must already be
         * zero (user input that silently widened would be a surprise); otherwise they are cleared.
         */
        fun parse(text: String, strict: Boolean = true): Cidr? {
            val slash = text.indexOf('/')
            val ip = IpAddress.parse(if (slash < 0) text else text.substring(0, slash)) ?: return null
            val max = if (ip.family == 4) 32 else 128
            val prefix = if (slash < 0) max else text.substring(slash + 1).let { p ->
                if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit) || (p.length > 1 && p[0] == '0')) return null
                p.toInt()
            }
            if (prefix !in 0..max) return null
            val cleared = ip.bytes.copyOf()
            var bits = prefix
            for (i in cleared.indices) {
                val keep = when {
                    bits >= 8 -> 0xff
                    bits <= 0 -> 0
                    else -> (0xff shl (8 - bits)) and 0xff
                }
                cleared[i] = (cleared[i].toInt() and keep).toByte()
                bits -= 8
            }
            if (strict && !cleared.contentEquals(ip.bytes)) return null
            return Cidr(IpAddress.of(cleared)!!, prefix)
        }
    }
}

object Domains {
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** Lowercase IDNA ASCII, one trailing dot stripped, label rules enforced. Null when invalid. */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.length > 300) return null
        if (s.endsWith('.')) s = s.dropLast(1)
        val ascii = try {
            IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES)
        } catch (_: IllegalArgumentException) {
            return null
        }.lowercase()
        if (ascii.isEmpty() || ascii.length > 253) return null
        val labels = ascii.split('.')
        if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return null
        return ascii
    }

    /** DNS label-boundary suffix match: "a.example.com" matches "example.com"; "badexample.com" does not. */
    fun matchesSuffix(domain: String, suffix: String): Boolean = domain == suffix || domain.endsWith(".$suffix")
}
