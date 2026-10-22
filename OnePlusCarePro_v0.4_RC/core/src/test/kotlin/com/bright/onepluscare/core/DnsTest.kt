package com.bright.onepluscare.core

import com.bright.onepluscare.core.dns.Blocklist
import com.bright.onepluscare.core.dns.Dns
import com.bright.onepluscare.core.dns.DnsPolicy
import com.bright.onepluscare.core.dns.DnsVerdict
import com.bright.onepluscare.core.dns.Packets
import com.bright.onepluscare.core.dns.UdpPacket
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsTest {
    private fun query(name: String, id: Int = 0x1234, qtype: Int = 1): ByteArray {
        val q = java.io.ByteArrayOutputStream()
        q.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { l -> q.write(l.length); q.write(l.toByteArray()) }
        q.write(0); q.write(byteArrayOf(0, qtype.toByte(), 0, 1))
        return q.toByteArray()
    }

    private val src4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dns4 = byteArrayOf(10, 111, 222.toByte(), 2)

    private fun v4(payload: ByteArray): ByteArray {
        val p = UdpPacket(4, dns4, src4, 53, 40000, payload) // build as if replying, then swap back
        return Packets.buildReply(p, payload)
    }

    @Test
    fun `ipv4 udp round trip with valid checksums`() {
        val pkt = v4(query("ads.example.com"))
        assertTrue(Packets.verifyIpv4Header(pkt))
        assertTrue(Packets.verifyUdp(pkt))
        val u = assertNotNull(Packets.parseUdp(pkt))
        assertEquals(53, u.dstPort)
        assertContentEquals(dns4, u.dst)
        val q = assertNotNull(Dns.parseQuery(u.payload))
        assertEquals("ads.example.com", q.name)
        assertEquals(0x1234, q.id)
        val reply = Packets.buildReply(u, Dns.errorReply(u.payload, Dns.RCODE_NXDOMAIN)!!)
        assertTrue(Packets.verifyIpv4Header(reply))
        assertTrue(Packets.verifyUdp(reply))
        val r = Packets.parseUdp(reply)!!
        assertEquals(40000, r.dstPort)
        assertContentEquals(src4, r.dst)
        assertEquals(0x80, r.payload[2].toInt() and 0x80, "QR set")
        assertEquals(3, r.payload[3].toInt() and 0x0f, "NXDOMAIN")
        assertEquals(0x12, r.payload[0].toInt() and 0xff)
    }

    @Test
    fun `ipv6 udp round trip with valid checksum`() {
        val a = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
        val b = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val pkt = Packets.buildReply(UdpPacket(6, b, a, 53, 5353, query("tracker.test")), query("tracker.test"))
        assertTrue(Packets.verifyUdp(pkt))
        val u = Packets.parseUdp(pkt)!!
        assertEquals("tracker.test", Dns.parseQuery(u.payload)!!.name)
    }

    @Test
    fun `malformed or non-dns input is rejected`() {
        assertNull(Packets.parseUdp(ByteArray(10)))
        val tcp = v4(query("a.b")).also { it[9] = 6 }
        assertNull(Packets.parseUdp(tcp))
        val frag = v4(query("a.b")).also { it[6] = 0x20 }
        assertNull(Packets.parseUdp(frag))
        assertNull(Dns.parseQuery(byteArrayOf(0, 1, 0x81.toByte(), 0, 0, 1, 0, 0, 0, 0, 0, 0)), "response, not query")
        val compressed = query("a.b").also { it[12] = 0xc0.toByte() }
        assertNull(Dns.parseQuery(compressed))
        val truncated = query("abc.example").copyOf(16)
        assertNull(Dns.parseQuery(truncated))
    }

    @Test
    fun `hosts parsing and exact matching with suffix exceptions`() {
        val list = Blocklist.parse(
            """
            # comment
            127.0.0.1 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 ads.example.com tracker.example.com # trailing
            0.0.0.0  Metrics.Example.NET.
            bare.example.org
            0.0.0.0 not_valid!
            """.trimIndent(),
        )
        assertEquals(4, list.size)
        assertTrue(list.contains("ads.example.com"))
        assertTrue(list.contains("metrics.example.net"))
        assertTrue(!list.contains("example.com"))
        assertTrue(!list.contains("localhost"))
        val policy = DnsPolicy(list, listOf("example.com"))
        assertEquals(DnsVerdict.ALLOWED_BY_EXCEPTION, policy.decide("ads.example.com"))
        assertEquals(DnsVerdict.BLOCK, policy.decide("METRICS.example.net."))
        assertEquals(DnsVerdict.ALLOW, policy.decide("www.example.org"))
    }

    @Test
    fun `oversized list is refused`() {
        assertEquals(0, Blocklist.parse("a".repeat(Blocklist.MAX_BYTES + 1)).size)
    }
}
