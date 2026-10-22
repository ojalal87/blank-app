package com.bright.onepluscare.core.dns

/**
 * Minimal IPv4/IPv6 + UDP handling for a DNS-only tunnel. Only what the filter needs: read a UDP datagram
 * addressed to the tunnel's DNS server, and build the reply datagram with correct checksums. Anything else
 * (TCP, fragments, options it cannot parse) is reported as null and dropped by the caller.
 */
data class UdpPacket(
    val ipVersion: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?) = other is UdpPacket && ipVersion == other.ipVersion && src.contentEquals(other.src) &&
        dst.contentEquals(other.dst) && srcPort == other.srcPort && dstPort == other.dstPort && payload.contentEquals(other.payload)
    override fun hashCode() = payload.contentHashCode()
}

object Packets {
    private const val UDP = 17

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    fun parseUdp(b: ByteArray, len: Int = b.size): UdpPacket? {
        if (len < 1) return null
        return when (u8(b, 0) shr 4) {
            4 -> {
                if (len < 20) return null
                val ihl = (u8(b, 0) and 0x0f) * 4
                val total = u16(b, 2)
                if (ihl < 20 || total > len || total < ihl + 8) return null
                if (u8(b, 9) != UDP) return null
                val fragment = u16(b, 6)
                if (fragment and 0x3fff != 0) return null // MF set or non-zero offset
                udp(4, b.copyOfRange(12, 16), b.copyOfRange(16, 20), b, ihl, total)
            }
            6 -> {
                if (len < 48) return null
                if (u8(b, 6) != UDP) return null // extension headers are not followed
                val payloadLen = u16(b, 4)
                if (40 + payloadLen > len) return null
                udp(6, b.copyOfRange(8, 24), b.copyOfRange(24, 40), b, 40, 40 + payloadLen)
            }
            else -> null
        }
    }

    private fun udp(v: Int, src: ByteArray, dst: ByteArray, b: ByteArray, off: Int, end: Int): UdpPacket? {
        val udpLen = u16(b, off + 4)
        if (udpLen < 8 || off + udpLen > end) return null
        return UdpPacket(v, src, dst, u16(b, off), u16(b, off + 2), b.copyOfRange(off + 8, off + udpLen))
    }

    /** Reply to [query]: addresses and ports swapped, new payload, IP and UDP checksums computed. */
    fun buildReply(query: UdpPacket, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        return if (query.ipVersion == 4) {
            val out = ByteArray(20 + udpLen)
            out[0] = 0x45
            put16(out, 2, out.size)
            out[6] = 0x40 // don't fragment
            out[8] = 64
            out[9] = UDP.toByte()
            query.dst.copyInto(out, 12)
            query.src.copyInto(out, 16)
            put16(out, 10, checksum(out, 0, 20, 0))
            writeUdp(out, 20, query, payload)
            put16(out, 26, udpChecksum(out, 20, udpLen, query.dst, query.src).let { if (it == 0) 0xffff else it })
            out
        } else {
            val out = ByteArray(40 + udpLen)
            out[0] = 0x60
            put16(out, 4, udpLen)
            out[6] = UDP.toByte()
            out[7] = 64
            query.dst.copyInto(out, 8)
            query.src.copyInto(out, 24)
            writeUdp(out, 40, query, payload)
            put16(out, 46, udpChecksum(out, 40, udpLen, query.dst, query.src).let { if (it == 0) 0xffff else it })
            out
        }
    }

    private fun writeUdp(out: ByteArray, off: Int, q: UdpPacket, payload: ByteArray) {
        put16(out, off, q.dstPort)
        put16(out, off + 2, q.srcPort)
        put16(out, off + 4, 8 + payload.size)
        payload.copyInto(out, off + 8)
    }

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte()
    }

    private fun sum(b: ByteArray, off: Int, len: Int, initial: Long): Long {
        var s = initial
        var i = off
        while (i + 1 < off + len) { s += u16(b, i); i += 2 }
        if (i < off + len) s += u8(b, i) shl 8
        return s
    }

    private fun fold(s0: Long): Int {
        var s = s0
        while (s shr 16 != 0L) s = (s and 0xffff) + (s shr 16)
        return (s.inv() and 0xffff).toInt()
    }

    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int = fold(sum(b, off, len, initial))

    private fun udpChecksum(b: ByteArray, off: Int, udpLen: Int, src: ByteArray, dst: ByteArray): Int {
        var s = sum(src, 0, src.size, 0) + sum(dst, 0, dst.size, 0) + UDP + udpLen
        s = sum(b, off, udpLen, s)
        return fold(s)
    }

    /** True when the checksum over [b] including its stored checksum is valid (used by tests). */
    fun verifyIpv4Header(b: ByteArray): Boolean = checksum(b, 0, (u8(b, 0) and 0x0f) * 4, 0) == 0
    fun verifyUdp(p: ByteArray): Boolean {
        val v = u8(p, 0) shr 4
        val off = if (v == 4) (u8(p, 0) and 0x0f) * 4 else 40
        val (src, dst) = if (v == 4) p.copyOfRange(12, 16) to p.copyOfRange(16, 20) else p.copyOfRange(8, 24) to p.copyOfRange(24, 40)
        val len = u16(p, off + 4)
        return udpChecksum(p, off, len, src, dst) == 0
    }
}

data class DnsQuestion(val id: Int, val name: String, val qtype: Int)

/** DNS message reading and the two replies the filter produces itself. */
object Dns {
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    /** First question of a standard query. null when not a query or malformed (compression in QNAME is invalid). */
    fun parseQuery(m: ByteArray): DnsQuestion? {
        if (m.size < 12) return null
        val flags = ((m[2].toInt() and 0xff) shl 8) or (m[3].toInt() and 0xff)
        if (flags and 0x8000 != 0) return null // QR=1: a response
        if ((flags shr 11) and 0xf != 0) return null // only standard queries
        val qd = ((m[4].toInt() and 0xff) shl 8) or (m[5].toInt() and 0xff)
        if (qd < 1) return null
        val sb = StringBuilder()
        var i = 12
        var total = 0
        while (true) {
            if (i >= m.size) return null
            val len = m[i].toInt() and 0xff
            if (len == 0) { i++; break }
            if (len > 63) return null
            if (i + 1 + len > m.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..len) {
                val c = (m[i + k].toInt() and 0xff).toChar()
                if (c.code < 33 || c.code > 126) return null
                sb.append(c.lowercaseChar())
            }
            total += len + 1
            if (total > 255) return null
            i += 1 + len
        }
        if (i + 4 > m.size) return null
        val qtype = ((m[i].toInt() and 0xff) shl 8) or (m[i + 1].toInt() and 0xff)
        val id = ((m[0].toInt() and 0xff) shl 8) or (m[1].toInt() and 0xff)
        return DnsQuestion(id, sb.toString(), qtype)
    }

    /** Copy of the query's header and question with QR, RA and the rcode set; no answers. */
    fun errorReply(query: ByteArray, rcode: Int): ByteArray? {
        if (parseQuery(query) == null) return null
        var end = 12
        while (query[end].toInt() != 0) end += 1 + (query[end].toInt() and 0xff)
        end += 5 // zero label + QTYPE + QCLASS
        val out = query.copyOfRange(0, end)
        val rd = out[2].toInt() and 0x01
        out[2] = (0x80 or rd).toByte() // QR=1, opcode 0, RD copied
        out[3] = (0x80 or (rcode and 0x0f)).toByte() // RA=1
        out[4] = 0; out[5] = 1 // one question
        for (k in 6..11) out[k] = 0 // no answer/authority/additional (drops EDNS OPT)
        return out
    }
}
