package com.rootguard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestSecurityTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
    private fun elements(tag: String) = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    @Test
    fun `offline core - no internet permission`() {
        val perms = elements("uses-permission").map { it.getAttributeNS(ns, "name") }
        assertFalse("android.permission.INTERNET" in perms)
        assertEquals(setOf("android.permission.QUERY_ALL_PACKAGES"), perms.toSet())
    }

    @Test
    fun `only the launcher activity is exported and backup is off`() {
        val exported = (elements("activity") + elements("service") + elements("receiver") + elements("provider")).filter { it.getAttributeNS(ns, "exported") == "true" }
        assertEquals(listOf(".MainActivity"), exported.map { it.getAttributeNS(ns, "name") })
        assertEquals("false", elements("application").single().getAttributeNS(ns, "allowBackup"))
    }

    @Test
    fun `helper AIDL has no command, path or script method`() {
        val aidl = File("src/main/aidl/com/rootguard/app/helper/IRootGuardHelper.aidl").readText()
        val methods = Regex("""(\w+)\s+(\w+)\(([^)]*)\)\s*=""").findAll(aidl).map { it.groupValues[2] }.toList()
        assertEquals(listOf("handshake", "call"), methods)
    }

    @Test
    fun `root process code never builds a shell command`() {
        val src = File("src/main/java/com/rootguard/app/helper/RootGuardHelperService.kt").readText() +
            File("../core/src/main/kotlin/com/rootguard/core/helper/HelperEngine.kt").readText()
        listOf("\"sh\"", "\"-c\"", "Runtime.getRuntime().exec", "su -c", "setenforce").forEach { assertFalse(it, src.contains(it)) }
        assertTrue(src.contains("ProcessBuilder(argv)"))
    }
}
