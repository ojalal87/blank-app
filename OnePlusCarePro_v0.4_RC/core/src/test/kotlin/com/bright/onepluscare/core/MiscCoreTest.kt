package com.bright.onepluscare.core

import com.bright.onepluscare.core.Fixtures.NOW
import com.bright.onepluscare.core.advanced.AdvancedAllowList
import com.bright.onepluscare.core.advanced.AdvancedDiagnostic
import com.bright.onepluscare.core.advanced.OutputSanitizer
import com.bright.onepluscare.core.apps.AppEvaluator
import com.bright.onepluscare.core.apps.AppFlag
import com.bright.onepluscare.core.apps.AppQuery
import com.bright.onepluscare.core.apps.AppRecord
import com.bright.onepluscare.core.apps.AppSort
import com.bright.onepluscare.core.apps.SensitivePermission
import com.bright.onepluscare.core.battery.BatteryInterpreter
import com.bright.onepluscare.core.battery.TemperatureBand
import com.bright.onepluscare.core.diagnostics.DiagnosticInput
import com.bright.onepluscare.core.diagnostics.DiagnosticOptions
import com.bright.onepluscare.core.diagnostics.DiagnosticReportBuilder
import com.bright.onepluscare.core.diagnostics.Redactor
import com.bright.onepluscare.core.health.HealthEvaluator
import com.bright.onepluscare.core.history.HistoryFilter
import com.bright.onepluscare.core.history.HistoryStore
import com.bright.onepluscare.core.history.HistoryType
import com.bright.onepluscare.core.io.AtomicTextFile
import com.bright.onepluscare.core.migration.LegacyMigration
import com.bright.onepluscare.core.model.Reading
import com.bright.onepluscare.core.model.Source
import com.bright.onepluscare.core.network.NetworkDiagnoser
import com.bright.onepluscare.core.network.NetworkLayer
import com.bright.onepluscare.core.network.LayerStatus
import com.bright.onepluscare.core.network.NetworkSnapshot
import com.bright.onepluscare.core.network.NetworkVerdict
import com.bright.onepluscare.core.network.PrivateDnsMode
import com.bright.onepluscare.core.network.Probe
import com.bright.onepluscare.core.network.ProbeStatus
import com.bright.onepluscare.core.model.Transport
import com.bright.onepluscare.core.oem.GuidanceItem
import com.bright.onepluscare.core.oem.OemDetector
import com.bright.onepluscare.core.oem.OemFamily
import com.bright.onepluscare.core.session.MaintenanceSession
import com.bright.onepluscare.core.settings.AppSettings
import com.bright.onepluscare.core.trash.TrashKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryStoreTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `events persist, filter by type and date, and are capped`() {
        var t = 1_000L
        val file = AtomicTextFile(tmp.resolve("h.json"))
        val store = HistoryStore(file, clock = { t++ }, maxEvents = 5)
        store.add(HistoryType.SCAN, count = 3)
        store.add(HistoryType.TRASH, count = 1, bytes = 10)
        store.add(HistoryType.PURGE, bytes = 10)
        val reopened = HistoryStore(file)
        assertEquals(3, reopened.list().size)
        assertEquals(listOf(HistoryType.PURGE, HistoryType.TRASH), reopened.list(HistoryFilter(types = HistoryFilter.CLEANING)).map { it.type })
        assertEquals(1, reopened.list(HistoryFilter(sinceMillis = 1_002)).size)
        repeat(10) { store.add(HistoryType.SCAN) }
        assertEquals(5, store.list().size)
    }

    @Test
    fun `atomic file falls back to backup when the main file is unreadable`() {
        val p = tmp.resolve("x.json")
        val f = AtomicTextFile(p)
        f.write("one")
        f.write("two")
        Files.delete(p)
        assertEquals("one", f.read())
    }
}

class NetworkDiagnoserTest {
    private fun snap(
        connected: Boolean = true,
        validated: Boolean = true,
        captive: Boolean = false,
        vpn: Boolean = false,
        privateDns: PrivateDnsMode = PrivateDnsMode.OPPORTUNISTIC,
        gateway: ProbeStatus = ProbeStatus.REACHABLE_REFUSED,
        dns: Probe = Probe(ProbeStatus.OK, 30),
        https: Probe = Probe(ProbeStatus.OK, 120),
    ) = NetworkSnapshot(connected, setOf(Transport.WIFI), vpn, validated, captive, false, privateDns, 2, true, Probe(gateway, 3), dns, https)

    @Test
    fun verdicts() {
        assertEquals(NetworkVerdict.HEALTHY, NetworkDiagnoser.diagnose(snap()).verdict)
        assertEquals(NetworkVerdict.OFFLINE, NetworkDiagnoser.diagnose(snap(connected = false)).verdict)
        assertEquals(NetworkVerdict.CAPTIVE_PORTAL, NetworkDiagnoser.diagnose(snap(captive = true)).verdict)
        assertEquals(NetworkVerdict.DNS_FAILURE, NetworkDiagnoser.diagnose(snap(dns = Probe(ProbeStatus.FAILED))).verdict)
        assertEquals(NetworkVerdict.PRIVATE_DNS_FAILURE, NetworkDiagnoser.diagnose(snap(privateDns = PrivateDnsMode.STRICT, dns = Probe(ProbeStatus.TIMEOUT))).verdict)
        assertEquals(NetworkVerdict.LOCAL_NETWORK_ONLY, NetworkDiagnoser.diagnose(snap(https = Probe(ProbeStatus.TIMEOUT))).verdict)
        assertEquals(NetworkVerdict.INTERNET_UNREACHABLE, NetworkDiagnoser.diagnose(snap(gateway = ProbeStatus.FAILED, dns = Probe(ProbeStatus.FAILED), https = Probe(ProbeStatus.FAILED))).verdict)
        assertEquals(NetworkVerdict.VPN_SUSPECTED, NetworkDiagnoser.diagnose(snap(vpn = true, gateway = ProbeStatus.TIMEOUT, https = Probe(ProbeStatus.FAILED))).verdict)
        assertEquals(NetworkVerdict.SLOW, NetworkDiagnoser.diagnose(snap(https = Probe(ProbeStatus.OK, 2_000))).verdict)
        assertEquals(NetworkVerdict.NOT_VALIDATED, NetworkDiagnoser.diagnose(snap(validated = false)).verdict)
    }

    @Test
    fun `gateway timeout is unknown, not a failure`() {
        val layers = NetworkDiagnoser.diagnose(snap(gateway = ProbeStatus.TIMEOUT)).layers
        assertEquals(LayerStatus.UNKNOWN, layers.first { it.layer == NetworkLayer.GATEWAY }.status)
    }

    @Test
    fun `connection refused proves the gateway is reachable`() {
        assertEquals(ProbeStatus.REACHABLE_REFUSED, NetworkDiagnoser.classifyGatewayError("connect failed: ECONNREFUSED (Connection refused)", false))
        assertEquals(ProbeStatus.TIMEOUT, NetworkDiagnoser.classifyGatewayError(null, true))
        assertEquals(ProbeStatus.FAILED, NetworkDiagnoser.classifyGatewayError("ENETUNREACH", false))
    }
}

class BatteryAndOemTest {
    @Test
    fun `temperature bands`() {
        assertEquals(TemperatureBand.NORMAL, BatteryInterpreter.temperatureBand(30f))
        assertEquals(TemperatureBand.WARM, BatteryInterpreter.temperatureBand(41f))
        assertEquals(TemperatureBand.HOT, BatteryInterpreter.temperatureBand(45f))
        assertEquals(TemperatureBand.COOL, BatteryInterpreter.temperatureBand(5f))
        assertEquals(TemperatureBand.UNAVAILABLE, BatteryInterpreter.temperatureBand(null))
        assertEquals(TemperatureBand.UNAVAILABLE, BatteryInterpreter.temperatureBand(-273f))
    }

    @Test
    fun `current now is always OEM-dependent and implausible values are dropped`() {
        assertIs<Reading.Unavailable>(BatteryInterpreter.normalizeCurrentNow(Int.MIN_VALUE))
        assertIs<Reading.Unavailable>(BatteryInterpreter.normalizeCurrentNow(0))
        assertEquals(Source.OEM_DEPENDENT, (BatteryInterpreter.normalizeCurrentNow(-450_000) as Reading.Measured).source)
    }

    @Test
    fun `oem detection`() {
        val op = OemDetector.detect("OnePlus", "OnePlus")
        assertEquals(OemFamily.ONEPLUS, op.family)
        assertTrue(GuidanceItem.PER_APP_BACKGROUND_ACTIVITY in op.guidance)
        assertTrue(op.aggressiveBackgroundPolicy)
        assertEquals(OemFamily.OPPO_FAMILY, OemDetector.detect("realme", "realme").family)
        val pixel = OemDetector.detect("Google", "google")
        assertFalse(pixel.aggressiveBackgroundPolicy)
        assertFalse(GuidanceItem.OPTIMIZED_CHARGING_INFO in pixel.guidance)
        assertEquals(OemFamily.OTHER, OemDetector.detect(null, null).family)
    }
}

class AppEvaluatorTest {
    private val day = 86_400_000L
    private fun app(pkg: String, system: Boolean = false, lastUsed: Long? = NOW - 100 * day, cache: Long? = 0, total: Long? = 100, fg: Long? = 0, perms: Set<SensitivePermission> = emptySet(), exempt: Boolean? = false) =
        AppRecord(pkg, pkg.uppercase(), system, true, NOW - 400 * day, NOW - 400 * day, total, 0, cache, fg, lastUsed, perms, exempt)

    @Test
    fun flags() {
        assertTrue(AppFlag.UNUSED_LONG in AppEvaluator.flags(app("a"), NOW, 90))
        assertFalse(AppFlag.UNUSED_LONG in AppEvaluator.flags(app("a", system = true), NOW, 90))
        assertTrue(AppFlag.LARGE_CACHE in AppEvaluator.flags(app("a", cache = 600_000_000), NOW, 30))
        assertTrue(AppFlag.SENSITIVE_AND_UNUSED in AppEvaluator.flags(app("a", perms = setOf(SensitivePermission.CAMERA)), NOW, 90))
        assertTrue(AppFlag.BATTERY_EXEMPT in AppEvaluator.flags(app("a", exempt = true, lastUsed = NOW), NOW, 30))
        // No usage record and short window: we do not claim "unused".
        assertFalse(AppFlag.UNUSED_LONG in AppEvaluator.flags(app("a", lastUsed = null, fg = null), NOW, 30))
    }

    @Test
    fun `search and sort with unknown values last`() {
        val apps = listOf(app("b.x", total = null), app("a.x", total = 10), app("c.x", total = 50), app("sys", system = true))
        assertEquals(listOf("c.x", "a.x", "b.x"), AppEvaluator.query(apps, AppQuery(sort = AppSort.TOTAL_SIZE), NOW, 30).map { it.packageName })
        assertEquals(listOf("a.x"), AppEvaluator.query(apps, AppQuery(text = "A.X"), NOW, 30).map { it.packageName })
        assertEquals(4, AppEvaluator.query(apps, AppQuery(includeSystem = true), NOW, 30).size)
    }
}

class DiagnosticsTest {
    private fun input(advanced: String? = null) = Fixtures.facts().let { f ->
        DiagnosticInput("2025-10-09T00:00:00Z", "0.4.0", f, HealthEvaluator.evaluate(f), null, mapOf("usageAccess" to true), advanced, mapOf("SCAN" to 2))
    }

    @Test
    fun `default export contains no identifiers and no advanced output`() {
        val json = DiagnosticReportBuilder.build(input(advanced = "uid=2000 /storage/emulated/0/DCIM/secret.jpg"), DiagnosticOptions())
        assertFalse(json.contains("secret.jpg"))
        assertFalse(json.contains("advanced\""))
        assertFalse(json.contains("historyCounts"))
        assertTrue(json.contains("\"containsDeviceIdentifiers\": false"))
    }

    @Test
    fun `opt-in advanced output is redacted`() {
        val json = DiagnosticReportBuilder.build(input(advanced = "path /storage/emulated/0/DCIM/secret.jpg ip 192.168.1.20 mac aa:bb:cc:dd:ee:ff user bob@example.com token=abc123 pkg com.whatsapp.android"), DiagnosticOptions(includeAdvanced = true))
        listOf("secret.jpg", "192.168.1.20", "aa:bb:cc", "bob@example.com", "abc123", "com.whatsapp").forEach { assertFalse(json.contains(it), it) }
    }

    @Test
    fun `device model can be omitted`() {
        val json = DiagnosticReportBuilder.build(input(), DiagnosticOptions(includeDeviceModel = false))
        assertFalse(json.contains("CPH2581"))
    }

    @Test
    fun `redactor keeps ordinary numbers`() {
        assertEquals("level: 80 temp: 315", Redactor.redact("level: 80 temp: 315"))
    }
}

class AdvancedAllowListTest {
    @Test
    fun `only exact enum names map to diagnostics`() {
        assertEquals(AdvancedDiagnostic.BATTERY, AdvancedDiagnostic.fromWireName("BATTERY"))
        listOf("battery", "BATTERY;reboot", "BATTERY && rm -rf /", "../BATTERY", "", null, "DISK -h /sdcard").forEach {
            assertNull(AdvancedDiagnostic.fromWireName(it), "must refuse $it")
        }
    }

    @Test
    fun `argv is fixed and never uses a shell`() {
        AdvancedDiagnostic.entries.forEach { d ->
            assertTrue(d.argv.first().startsWith("/system/bin/"))
            assertFalse(d.argv.any { it == "sh" || it.endsWith("/sh") || it == "-c" })
        }
    }

    @Test
    fun `property allow-list refuses identifiers and injection`() {
        assertTrue(AdvancedAllowList.isAllowedProperty("ro.build.version.security_patch"))
        listOf("ro.serialno", "ro.boot.serialno", "persist.sys.timezone", "ro.build.version.sdk; reboot", "", null).forEach {
            assertFalse(AdvancedAllowList.isAllowedProperty(it), "must refuse $it")
        }
        assertNull(AdvancedAllowList.propertyArgv("ro.serialno"))
    }

    @Test
    fun `output sanitizer caps lines and redacts serials and MACs`() {
        val raw = (1..100).joinToString("\n") { "line $it" } + "\nserialno: ABC123\nwlan aa:bb:cc:dd:ee:ff"
        assertEquals(10, OutputSanitizer.sanitize(raw, 10).lines().size)
        val tail = OutputSanitizer.sanitize("serialno: ABC123\nwlan aa:bb:cc:dd:ee:ff", 10)
        assertFalse(tail.contains("ABC123"))
        assertFalse(tail.contains("aa:bb"))
    }
}

class MigrationTest {
    @Test
    fun `v0_3 history converts and drops free-text details`() {
        val raw = """[{"id":"1","time":5,"type":"restore","title":"استعادة ملف","detail":"/storage/emulated/0/DCIM/private.jpg","bytes":0},
            {"id":"2","time":4,"type":"trash","title":"t","detail":"d","bytes":-5},{"bogus":true}, 7]"""
        val events = LegacyMigration.parseHistory(raw)
        assertEquals(3, events.size)
        assertEquals(HistoryType.RESTORE, events[0].type)
        assertFalse(events.any { it.legacyText?.contains("private.jpg") == true })
        assertEquals(0L, events[1].bytes)
        assertTrue(LegacyMigration.parseHistory("not json").isEmpty())
        assertTrue(LegacyMigration.parseHistory(null).isEmpty())
    }

    @Test
    fun `v0_3 trash imports only entries inside the trash dir`() {
        val dir = "/storage/emulated/0/Android/data/com.bright.onepluscare/files/safe_trash"
        val raw = """[
          {"id":"a","kind":"FILE","name":"x.pdf","originalPath":"/storage/emulated/0/Download/x.pdf","trashPath":"$dir/0f8fad5b-d9cb-469f-a165-70867728950e_x.pdf","size":10,"time":1,"category":"c"},
          {"id":"b","kind":"FILE","name":"y","originalPath":"/storage/emulated/0/y","trashPath":"/data/local/tmp/y","size":1,"time":1},
          {"id":"c","kind":"MEDIA","name":"p.jpg","uri":"content://media/external/images/media/9","size":3,"time":1},
          {"id":"d","kind":"MEDIA","name":"p.jpg","uri":"content://evil.provider/x","size":3,"time":1}
        ]"""
        val entries = LegacyMigration.parseTrash(raw, dir)
        assertEquals(listOf("a", "c"), entries.map { it.id })
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e_x.pdf", entries[0].trashFileName)
        assertEquals(TrashKind.MEDIA, entries[1].kind)
    }
}

class SettingsAndSessionTest {
    @Test
    fun `settings sanitize out-of-range values`() {
        val s = AppSettings(trashRetentionDays = 3, largeFileMinMb = 7, largeFileMinAgeDays = 11, exclusions = listOf("../x", "DCIM", "DCIM/")).sanitized()
        assertNull(s.trashRetentionDays)
        assertEquals(250, s.largeFileMinMb)
        assertEquals(0, s.largeFileMinAgeDays)
        assertEquals(listOf("DCIM/"), s.exclusions)
        assertEquals(250_000_000L, s.scanConfig().largeFileMinBytes)
    }

    @Test
    fun `session attributes only measured purges`() {
        val r = MaintenanceSession(0, freeBytesBefore = 1_000_000_000)
            .recordTrash(500_000_000, 3)
            .recordPurge(200_000_000)
            .finish(freeBytesAfter = 1_200_000_000, endedAtMillis = 10)
        assertEquals(200_000_000, r.attributedFreedBytes)
        assertEquals(500_000_000, r.pendingInTrashBytes)
        assertFalse(r.externalActivityLikely)
        val noisy = MaintenanceSession(0, 1_000_000_000).finish(400_000_000, 1)
        assertTrue(noisy.externalActivityLikely)
        assertEquals(0, noisy.attributedFreedBytes)
    }
}
