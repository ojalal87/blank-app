package com.bright.onepluscare.core

import com.bright.onepluscare.core.hardening.CheckId
import com.bright.onepluscare.core.hardening.CheckState
import com.bright.onepluscare.core.hardening.HardeningEvaluator
import com.bright.onepluscare.core.hardening.HardeningFacts
import com.bright.onepluscare.core.hardening.WifiSecurity
import com.bright.onepluscare.core.network.PrivateDnsMode
import com.bright.onepluscare.core.privacy.AccessState
import com.bright.onepluscare.core.privacy.AppOpsHistory
import com.bright.onepluscare.core.privacy.PrivacyOp
import com.bright.onepluscare.core.security.Surface
import com.bright.onepluscare.core.security.WatchAlert
import com.bright.onepluscare.core.security.WatchRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppOpsHistoryTest {
    private val android14 = """
        Current AppOps Service state:
          Settings:
            top_state_settle_time=+5s0ms
          Uid u0a123:
            state=top
            Package com.example.chat:
              RECORD_AUDIO (allow):
                null=[
                  Access: [top-s] 2026-10-22 18:00:00.123 (-2h3m4s5ms) duration=+30s
                  Access: [bg-s] 2026-10-22 03:00:00.000 (-17h3m4s5ms) duration=+5s
                ]
              CAMERA (allow):
                null=[
                  Access: [top-s] 2026-10-22 19:00:00.000 (-1h0m0s0ms) duration=+1s
                ]
          Uid u0a200:
            Package com.spy.recorder:
              RECORD_AUDIO (allow):
                rec=[
                  Access: [cch-s] 2026-10-22 20:00:00.000 (-5m0s0ms) duration=+59s
                  Reject: [bg-s] 2026-10-22 19:00:00.000 (-1h5m0s0ms)
                ]
    """.trimIndent()

    @Test
    fun `android 11+ format with attribution tags`() {
        val mic = AppOpsHistory.parse(android14, PrivacyOp.MICROPHONE)
        assertEquals(listOf("com.spy.recorder", "com.example.chat"), mic.map { it.packageName })
        assertEquals(5 * 60_000L, mic[0].agoMs)
        assertEquals(AccessState.BACKGROUND, mic[0].state)
        val chat = mic[1]
        assertEquals(2 * 3_600_000L + 3 * 60_000 + 4_000 + 5, chat.agoMs)
        assertEquals(AccessState.FOREGROUND, chat.state)
        assertEquals(2, chat.records)
        assertTrue(chat.anyBackground)
        assertEquals(listOf("com.example.chat"), AppOpsHistory.parse(android14, PrivacyOp.CAMERA).map { it.packageName })
    }

    @Test
    fun `android 10 legacy format`() {
        val legacy = "  Uid u0a5:\n    Package com.old.app:\n      CAMERA (allow): time=+1d2h ago; duration=+5s\n"
        val cam = AppOpsHistory.parse(legacy, PrivacyOp.CAMERA).single()
        assertEquals(26 * 3_600_000L, cam.agoMs)
    }

    @Test
    fun `durations and garbage`() {
        assertEquals(1_000L * 60 * 60 * 24 + 1, AppOpsHistory.parseDuration("-1d1ms"))
        assertEquals(null, AppOpsHistory.parseDuration("-abc"))
        assertTrue(AppOpsHistory.parse("random\nlines\n  Package bad name:\n", PrivacyOp.CAMERA).isEmpty())
        val enc = AppOpsHistory.encode(AppOpsHistory.parse(android14, PrivacyOp.MICROPHONE))
        assertEquals(2, AppOpsHistory.decode(enc)!!.size)
        assertEquals(null, AppOpsHistory.decode("{\"x\":1}"))
    }
}

class HardeningTest {
    private val good = HardeningFacts(true, 30, false, false, true, false, emptyList(), WifiSecurity.SECURED, PrivateDnsMode.OPPORTUNISTIC, false)

    @Test
    fun `good device is all ok`() {
        assertTrue(HardeningEvaluator.evaluate(good).all { it.state == CheckState.OK })
    }

    @Test
    fun `open wifi, no lock and old patch are critical and sorted first`() {
        val r = HardeningEvaluator.evaluate(good.copy(screenLockSecure = false, patchAgeDays = 400, wifi = WifiSecurity.OPEN, usbDebugging = true, shizukuRunning = true))
        assertEquals(setOf(CheckId.SCREEN_LOCK, CheckId.PATCH_AGE, CheckId.WIFI_SECURITY), r.takeWhile { it.state == CheckState.CRITICAL }.map { it.id }.toSet())
        assertEquals("shizuku", r.first { it.id == CheckId.USB_DEBUGGING }.detail)
    }

    @Test
    fun `unreadable facts are unknown, never ok`() {
        val r = HardeningEvaluator.evaluate(good.copy(storageEncrypted = null, lockScreenShowsContent = null, wifi = WifiSecurity.UNKNOWN))
        assertEquals(CheckState.UNKNOWN, r.first { it.id == CheckId.STORAGE_ENCRYPTION }.state)
        assertEquals(CheckState.UNKNOWN, r.first { it.id == CheckId.WIFI_SECURITY }.state)
    }
}

class WatchRulesTest {
    @Test
    fun `first run is a baseline, later grants alert once`() {
        val now = mapOf(Surface.ACCESSIBILITY to setOf("a.b.c", SELF_PKG))
        assertTrue(WatchRules.newSpecialAccess(null, now, SELF_PKG).isEmpty())
        val alerts = WatchRules.newSpecialAccess(mapOf(Surface.ACCESSIBILITY to emptySet()), now, SELF_PKG)
        assertEquals(listOf("a.b.c"), alerts.map { it.packageName })
        assertTrue(WatchRules.fresh(alerts, alerts.map { it.key }.toSet()).isEmpty())
        assertEquals(WatchAlert.Kind.NEW_SPECIAL_ACCESS, alerts.single().kind)
    }
}

private const val SELF_PKG = "com.bright.onepluscare"
