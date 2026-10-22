package com.rootguard.core

import com.rootguard.core.net.Cidr
import com.rootguard.core.net.Domains
import com.rootguard.core.net.IpAddress
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetTest {
    @Test
    fun `ipv4 parsing is strict`() {
        assertEquals("192.168.1.10", IpAddress.parse("192.168.1.10").toString())
        listOf("256.1.1.1", "1.2.3", "01.2.3.4", "1.2.3.4.5", "", "a.b.c.d", "1.2.3.-1").forEach { assertNull(IpAddress.parse(it), it) }
    }

    @Test
    fun `ipv6 parsing and RFC 5952 formatting`() {
        assertEquals("2001:db8::1", IpAddress.parse("2001:0DB8:0000:0000:0000:0000:0000:0001").toString())
        assertEquals("::1", IpAddress.parse("::1").toString())
        assertEquals("::", IpAddress.parse("::").toString())
        assertEquals("::ffff:1.2.3.4".let { IpAddress.parse(it)!!.bytes.toList() }, IpAddress.parse("::ffff:102:304")!!.bytes.toList())
        assertEquals("2001:db8:0:1:1:1:1:1", IpAddress.parse("2001:db8:0:1:1:1:1:1").toString())
        assertEquals("fe80::1:0:0:1", IpAddress.parse("fe80:0:0:0:1:0:0:1").toString())
        listOf("1::2::3", "12345::", "fe80::1%wlan0", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "::g").forEach { assertNull(IpAddress.parse(it), it) }
    }

    @Test
    fun `cidr host bits must be clear in strict mode`() {
        assertEquals("10.0.0.0/8", Cidr.parse("10.0.0.0/8").toString())
        assertNull(Cidr.parse("10.0.0.1/8"))
        assertEquals("10.0.0.0/8", Cidr.parse("10.0.0.1/8", strict = false).toString())
        assertEquals("2001:db8::/32", Cidr.parse("2001:db8::/32").toString())
        assertNull(Cidr.parse("10.0.0.0/33"))
        assertNull(Cidr.parse("10.0.0.0/08"))
        assertEquals("1.2.3.4/32", Cidr.parse("1.2.3.4").toString())
        assertTrue(Cidr.parse("192.168.0.0/16")!!.contains(IpAddress.parse("192.168.44.1")!!))
        assertFalse(Cidr.parse("192.168.0.0/16")!!.contains(IpAddress.parse("192.169.0.1")!!))
        assertTrue(Cidr.parse("0.0.0.0/0")!!.contains(IpAddress.parse("8.8.8.8")!!))
    }

    @Test
    fun `domains normalise to IDNA ASCII with label boundary suffix matching`() {
        assertEquals("example.com", Domains.normalize("Example.COM."))
        assertEquals("xn--mgbh0fb.example", Domains.normalize("مثال.example"))
        listOf("", "-a.com", "a..com", "localhost", "a_b.com", "x".repeat(64) + ".com").forEach { assertNull(Domains.normalize(it), it) }
        assertTrue(Domains.matchesSuffix("a.example.com", "example.com"))
        assertTrue(Domains.matchesSuffix("example.com", "example.com"))
        assertFalse(Domains.matchesSuffix("badexample.com", "example.com"))
    }
}
