package com.bright.onepluscare.core

import com.bright.onepluscare.core.security.AppRiskScanner
import com.bright.onepluscare.core.security.AppSecurityFacts
import com.bright.onepluscare.core.security.FindingId
import com.bright.onepluscare.core.security.FirewallRules
import com.bright.onepluscare.core.security.IocDatabase
import com.bright.onepluscare.core.security.ThreatLevel
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SELF = "com.bright.onepluscare"

class IocDatabaseTest {
    private val sample = """
        - name: TheTruthSpy
          names:
          - Copy9
          type: stalkerware
          packages:
          - com.thetruth
          - "com.ispyoo"
          - bad;package
          certificates:
          - 31A6ECECD97CF39BC4126B8745CD94A7C30BF81C
          - 31:a6:ec:ec:d9:7c:f3:9b:c4:12:6b:87:45:cd:94:a7:c3:0b:f8:1d
          - nothex
          websites:
          - com.notapackage.example
        - name: Empty
          type: stalkerware
        - name: Other
          packages:
          - org.spy.app
    """.trimIndent()

    @Test
    fun `parses packages and certificates, ignores websites and junk`() {
        val db = IocDatabase.parse(sample)
        assertEquals(listOf("TheTruthSpy", "Other"), db.entries.map { it.name })
        assertEquals(setOf("com.thetruth", "com.ispyoo"), db.entries[0].packages)
        assertEquals(2, db.entries[0].certificates.size)
        assertEquals("TheTruthSpy", db.match("com.unrelated", listOf("31:A6:EC:EC:D9:7C:F3:9B:C4:12:6B:87:45:CD:94:A7:C3:0B:F8:1C"))?.name)
        assertNull(db.match("com.notapackage.example", emptyList()))
        assertEquals("Other", db.match("org.spy.app", emptyList())?.name)
    }

    @Test
    fun `bundled snapshot parses into a plausible database`() {
        val file = File("../app/src/main/assets/stalkerware_ioc.yaml")
        val db = IocDatabase.parse(file.readText())
        assertTrue(db.entries.size >= IocDatabase.MIN_ENTRIES_FOR_UPDATE, "entries=${db.entries.size}")
        assertTrue(db.packageCount > 500)
        assertTrue(db.certificateCount > 100)
        assertNotNull(db.match("com.thetruth", emptyList()))
    }

    @Test
    fun `oversized input is rejected`() {
        assertTrue(IocDatabase.parse("x".repeat(IocDatabase.MAX_BYTES + 1)).entries.isEmpty())
    }
}

class AppRiskScannerTest {
    private val ioc = IocDatabase.parse("- name: SpyX\n  packages:\n  - com.spy.x\n")

    private fun app(
        pkg: String = "com.example.app",
        system: Boolean = false,
        installer: String? = "com.android.vending",
        granted: Set<String> = emptySet(),
        requested: Set<String> = granted,
        launcher: Boolean = true,
        a11y: Boolean = false,
        nls: Boolean = false,
        admin: Boolean = false,
        target: Int = 34,
    ) = AppSecurityFacts(pkg, pkg, system, installer, granted, requested, launcher, target, false, emptyList(), a11y, nls, admin, false, false, null)

    @Test
    fun `ordinary Play app is clean`() {
        assertEquals(ThreatLevel.NONE, AppRiskScanner.assess(app(), ioc, SELF).level)
    }

    @Test
    fun `known stalkerware is critical`() {
        val a = AppRiskScanner.assess(app(pkg = "com.spy.x"), ioc, SELF)
        assertEquals(ThreatLevel.CRITICAL, a.level)
        assertEquals("SpyX", a.stalkerwareFamily)
    }

    @Test
    fun `banking trojan capability pattern is high`() {
        val a = AppRiskScanner.assess(
            app(installer = null, granted = setOf("android.permission.RECEIVE_SMS"), requested = setOf("android.permission.RECEIVE_SMS", "android.permission.SYSTEM_ALERT_WINDOW"), a11y = true),
            ioc, SELF,
        )
        assertEquals(ThreatLevel.HIGH, a.level)
        assertTrue(a.findings.any { it.id == FindingId.BANKING_TROJAN_PATTERN })
        assertTrue(a.findings.any { it.id == FindingId.SIDELOADED })
    }

    @Test
    fun `hidden app with surveillance permissions is high`() {
        val a = AppRiskScanner.assess(app(launcher = false, granted = setOf("android.permission.RECORD_AUDIO", "android.permission.ACCESS_BACKGROUND_LOCATION")), ioc, SELF)
        assertEquals(ThreatLevel.HIGH, a.level)
    }

    @Test
    fun `trusted-store accessibility app is only low, sideloaded is medium`() {
        assertEquals(ThreatLevel.LOW, AppRiskScanner.assess(app(a11y = true), ioc, SELF).level)
        assertEquals(ThreatLevel.MEDIUM, AppRiskScanner.assess(app(a11y = true, installer = "com.google.android.packageinstaller"), ioc, SELF).level)
    }

    @Test
    fun `system apps and this app are never flagged`() {
        assertEquals(ThreatLevel.NONE, AppRiskScanner.assess(app(pkg = "com.spy.x", system = true, a11y = true), ioc, SELF).level)
        val s = AppRiskScanner.scan(listOf(app(pkg = SELF, installer = null), app()), ioc, SELF)
        assertEquals(1, s.scannedApps)
    }

    @Test
    fun `legacy target sdk bypasses runtime permissions`() {
        assertTrue(AppRiskScanner.assess(app(target = 22), ioc, SELF).findings.any { it.id == FindingId.LEGACY_PERMISSION_MODEL })
    }

    @Test
    fun `scan summary groups spyware capabilities`() {
        val s = AppRiskScanner.scan(listOf(app(pkg = "a.b.c", a11y = true), app(pkg = "d.e.f", nls = true), app(pkg = "g.h.i", admin = true)), ioc, SELF)
        assertEquals(1, s.accessibilityApps.size)
        assertEquals(1, s.notificationListenerApps.size)
        assertEquals(1, s.deviceAdminApps.size)
        assertEquals(ThreatLevel.LOW, s.worst)
    }
}

class FirewallRulesTest {
    @Test
    fun `empty block list can never start the VPN`() {
        assertFalse(FirewallRules.canStart(emptySet()))
        assertTrue(FirewallRules.canStart(setOf("com.x.y")))
    }

    @Test
    fun `sanitize drops invalid names and this app`() {
        assertEquals(setOf("com.x.y"), FirewallRules.sanitize(listOf("com.x.y", "bad name", SELF, ""), SELF))
    }
}
