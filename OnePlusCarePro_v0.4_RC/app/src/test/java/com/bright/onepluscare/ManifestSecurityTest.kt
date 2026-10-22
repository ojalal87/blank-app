package com.bright.onepluscare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Security regression tests over the source manifest and XML config (no device needed). */
class ManifestSecurityTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun doc(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path))

    private val manifest by lazy { doc("src/main/AndroidManifest.xml") }

    private fun elements(tag: String): List<Element> {
        val nl = manifest.getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    @Test
    fun `only the launcher activity and the permission-protected Shizuku provider are exported`() {
        val exported = (elements("activity") + elements("service") + elements("receiver") + elements("provider"))
            .filter { it.getAttributeNS(ns, "exported") == "true" }
        assertEquals(setOf(".MainActivity", "rikka.shizuku.ShizukuProvider"), exported.map { it.getAttributeNS(ns, "name") }.toSet())
        val provider = exported.first { it.getAttributeNS(ns, "name") == "rikka.shizuku.ShizukuProvider" }
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.getAttributeNS(ns, "permission"))
    }

    @Test
    fun `main activity has no deep links`() {
        val activity = elements("activity").single()
        val filters = activity.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        assertEquals(0, (filters.item(0) as Element).getElementsByTagName("data").length)
    }

    @Test
    fun `firewall VPN service is bindable only by the system`() {
        val svc = elements("service").single { it.getAttributeNS(ns, "name") == ".firewall.FirewallVpnService" }
        assertEquals("false", svc.getAttributeNS(ns, "exported"))
        assertEquals("android.permission.BIND_VPN_SERVICE", svc.getAttributeNS(ns, "permission"))
        // No boot receiver: the firewall never starts itself.
        val perms = elements("uses-permission").map { it.getAttributeNS(ns, "name") }
        assertFalse("android.permission.RECEIVE_BOOT_COMPLETED" in perms)
        assertTrue(elements("receiver").isEmpty())
    }

    @Test
    fun `no cleartext traffic`() {
        val app = elements("application").single()
        assertEquals("false", app.getAttributeNS(ns, "usesCleartextTraffic"))
        val nsc = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(nsc.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(nsc.contains("src=\"user\""))
    }

    @Test
    fun `legacy write permission is capped at Android 10`() {
        val write = elements("uses-permission").first { it.getAttributeNS(ns, "name") == "android.permission.WRITE_EXTERNAL_STORAGE" }
        assertEquals("29", write.getAttributeNS(ns, "maxSdkVersion"))
    }

    @Test
    fun `backup includes only settings, never trash or history`() {
        listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml").forEach { path ->
            val text = File(path).readText()
            assertTrue(text.contains("care_settings.xml"))
            assertFalse(text.contains("trash_journal"))
            assertFalse(text.contains("history.json"))
            assertFalse(text.contains("domain=\"external\""))
        }
    }

    @Test
    fun `AIDL exposes no command, path or argument-list method`() {
        val aidl = File("src/main/aidl/com/bright/onepluscare/IAdvancedMaintenanceService.aidl").readText()
        val methods = Regex("""\b(String|void)\s+(\w+)\s*\(""").findAll(aidl).map { it.groupValues[2] }.toSet()
        assertEquals(setOf("identity", "readProperty", "runDiagnostic", "runOp", "destroy"), methods)
        assertFalse(aidl.contains("List<"))
        assertFalse(aidl.contains("String[]"))
    }

    @Test
    fun `user service never invokes a shell`() {
        val src = File("src/main/java/com/bright/onepluscare/shizuku/AdvancedMaintenanceService.kt").readText()
        assertFalse(src.contains("\"sh\""))
        assertFalse(src.contains("\"-c\""))
        assertFalse(src.contains("Runtime.getRuntime().exec"))
        assertTrue(src.contains("enforceCaller()"))
        // Every mutation is re-validated in the privileged process before execution.
        assertTrue(src.contains("TweakCommands.argvFor(op, selfPackage) ?: return OpResult.encode(OpResult.REFUSED)"))
    }

    @Test
    fun `scheduled worker is read-only`() {
        val src = File("src/main/java/com/bright/onepluscare/work/MaintenanceScanWorker.kt").readText()
        listOf("moveToSafeTrash", "purge(", "expire(", "restore(", "systemTrashRequest", ".delete(").forEach {
            assertFalse("worker must not call $it", src.contains(it))
        }
    }

    @Test
    fun `pending intents are immutable`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        sources.filter { it.contains("PendingIntent.get") }.forEach { assertTrue(it.contains("FLAG_IMMUTABLE")) }
    }
}
