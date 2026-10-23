package com.bright.onepluscare.core

import com.bright.onepluscare.core.io.AtomicTextFile
import com.bright.onepluscare.core.tweaks.ChangeStatus
import com.bright.onepluscare.core.tweaks.DebloatClassifier
import com.bright.onepluscare.core.tweaks.DebloatRisk
import com.bright.onepluscare.core.tweaks.OpResult
import com.bright.onepluscare.core.tweaks.OutputChecks
import com.bright.onepluscare.core.tweaks.PackageInventory
import com.bright.onepluscare.core.tweaks.PackageListFilter
import com.bright.onepluscare.core.tweaks.PackageState
import com.bright.onepluscare.core.tweaks.SettingNamespace
import com.bright.onepluscare.core.tweaks.TweakCommands
import com.bright.onepluscare.core.tweaks.TweakExecutor
import com.bright.onepluscare.core.tweaks.TweakJournal
import com.bright.onepluscare.core.tweaks.TweakKind
import com.bright.onepluscare.core.tweaks.TweakOp
import com.bright.onepluscare.core.tweaks.TweakParsers
import com.bright.onepluscare.core.tweaks.TweakPlanner
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SELF = "com.bright.onepluscare"

class TweakCommandsTest {
    @Test
    fun `only allow-listed settings with valid values map to argv`() {
        assertEquals(
            listOf("/system/bin/settings", "put", "global", "window_animation_scale", "0.5"),
            TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.GLOBAL, "window_animation_scale", "0.5"), SELF),
        )
        assertNull(TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.GLOBAL, "window_animation_scale", "0.5; reboot"), SELF))
        assertNull(TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.GLOBAL, "window_animation_scale", "9"), SELF))
        assertNull(TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.SECURE, "enabled_accessibility_services", "x/y"), SELF))
        assertNull(TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.GLOBAL, "adb_enabled", "1"), SELF))
        assertNull(TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.SYSTEM, "window_animation_scale", "0.5"), SELF), "namespace must match")
        assertNull(TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.SYSTEM, "peak_refresh_rate", "1000"), SELF))
        assertNull(TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.GLOBAL, "private_dns_specifier", "evil.com\nreboot"), SELF))
        assertNull(TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.GLOBAL, "private_dns_specifier", "UPPER.example.com"), SELF))
        assertNotNull(TweakCommands.argvFor(TweakOp.SettingPut(SettingNamespace.GLOBAL, "private_dns_specifier", "dns.adguard-dns.com"), SELF))
    }

    @Test
    fun `package fields are validated and argv never contains a shell`() {
        val bad = listOf("com.x;reboot", "../etc", "com.x --user 10", "", "a", "com.x\ncom.y", "-f")
        bad.forEach { p ->
            assertNull(TweakCommands.argvFor(TweakOp.PackageDisable(p), SELF), p)
            assertNull(TweakCommands.argvFor(TweakOp.AppOpSet(p, "RUN_IN_BACKGROUND", "allow"), SELF), p)
            assertNull(TweakCommands.argvFor(TweakOp.IdleWhitelistAdd(p), SELF), p)
        }
        assertNull(TweakCommands.argvFor(TweakOp.AppOpSet("com.whatsapp", "CAMERA", "allow"), SELF))
        assertNull(TweakCommands.argvFor(TweakOp.AppOpSet("com.whatsapp", "RUN_IN_BACKGROUND", "foreground"), SELF))
        assertNull(TweakCommands.argvFor(TweakOp.StandbySet("com.whatsapp", "exempted"), SELF))
        val all = listOf(
            TweakOp.IdleWhitelistList, TweakOp.DensityGet, TweakOp.DensityReset, TweakOp.DensitySet(420),
            TweakOp.PackagesList(PackageListFilter.SYSTEM), TweakOp.PackageEnable("com.x.y"), TweakOp.StandbyGet("com.x.y"),
        )
        all.forEach { op ->
            val argv = TweakCommands.argvFor(op, SELF)!!
            assertTrue(argv.first().startsWith("/system/bin/"))
            assertFalse(argv.any { it == "sh" || it == "-c" || it.contains(';') || it.contains('|') })
        }
    }

    @Test
    fun `protected packages, this app and Shizuku can never be removed`() {
        listOf(
            "com.android.systemui", "com.android.phone", "com.google.android.gms", "com.android.vending",
            "com.android.providers.media.module", "com.google.android.cellbroadcastreceiver", "com.oplus.launcher",
            "com.google.android.inputmethod.latin", "com.qualcomm.qti.telephonyservice", SELF, TweakCommands.SHIZUKU_PACKAGE,
        ).forEach { p ->
            assertNull(TweakCommands.argvFor(TweakOp.PackageDisable(p), SELF), p)
            assertNull(TweakCommands.argvFor(TweakOp.PackageUninstallUser(p), SELF), p)
        }
        assertNotNull(TweakCommands.argvFor(TweakOp.PackageDisable("com.facebook.appmanager"), SELF))
        // Enabling/reinstalling is always allowed, so recovery is possible.
        assertNotNull(TweakCommands.argvFor(TweakOp.PackageEnable("com.android.systemui"), SELF))
    }

    @Test
    fun `density limits`() {
        assertNull(TweakCommands.argvFor(TweakOp.DensitySet(50), SELF))
        assertNull(TweakCommands.argvFor(TweakOp.DensitySet(5000), SELF))
    }

    @Test
    fun `ops round-trip through JSON and hostile JSON is rejected`() {
        val op = TweakOp.AppOpSet("com.whatsapp", "RUN_ANY_IN_BACKGROUND", "allow")
        assertEquals(op, TweakOp.decode(TweakOp.encode(op)))
        assertEquals(TweakOp.IdleWhitelistList, TweakOp.decode(TweakOp.encode(TweakOp.IdleWhitelistList)))
        listOf(null, "", "{}", """{"type":"exec","cmd":"reboot"}""", """{"type":"package_disable"}""", "x".repeat(5000)).forEach {
            assertNull(TweakOp.decode(it), it?.take(40))
        }
    }

    @Test
    fun `exit 0 with error text counts as failure`() {
        assertTrue(OutputChecks.looksFailed(TweakOp.PackageDisable("a.b"), "Exception occurred while executing 'disable-user'"))
        assertTrue(OutputChecks.looksFailed(TweakOp.PackageUninstallUser("a.b"), "Failure [not installed for 0]"))
        assertFalse(OutputChecks.looksFailed(TweakOp.PackageUninstallUser("a.b"), "Success"))
        assertFalse(OutputChecks.looksFailed(TweakOp.SettingGet(SettingNamespace.GLOBAL, "x"), "Exception"))
    }
}

class TweakParsersTest {
    @Test
    fun parsers() {
        assertNull(TweakParsers.settingValue("null\n"))
        assertEquals("0.5", TweakParsers.settingValue("0.5\n"))
        assertEquals("ignore", TweakParsers.appOpMode("RUN_ANY_IN_BACKGROUND: ignore; time=+2d3h ago\n", "RUN_ANY_IN_BACKGROUND"))
        assertEquals("default", TweakParsers.appOpMode("No operations.\n", "RUN_IN_BACKGROUND"))
        assertEquals("default", TweakParsers.appOpMode("RUN_IN_BACKGROUND: foreground\n", "RUN_IN_BACKGROUND"))
        val wl = TweakParsers.idleWhitelist("system-excidle,com.android.shell,2000\nsystem,com.google.android.gms,10150\nuser,com.whatsapp,10234\n")
        assertTrue("com.whatsapp" in wl.user)
        assertTrue(wl.contains("com.google.android.gms"))
        assertEquals(40, TweakParsers.standbyBucket("40\n"))
        assertEquals(setOf("com.a.b", "com.c.d"), TweakParsers.packageList("package:com.a.b\npackage:com.c.d\njunk\npackage:bad;x\n"))
        val d = TweakParsers.density("Physical density: 480\nOverride density: 420\n")
        assertEquals(480, d.physical); assertEquals(420, d.effective)
        assertNull(TweakParsers.density("Physical density: 480").override)
    }

    @Test
    fun `inventory marks disabled and uninstalled system packages`() {
        val inv = PackageInventory.build(
            systemOut = "package:com.facebook.appmanager\npackage:com.android.systemui\npackage:com.oplus.foo\n",
            disabledOut = "package:com.oplus.foo\n",
            systemAllOut = "package:com.facebook.appmanager\npackage:com.android.systemui\npackage:com.oplus.foo\npackage:com.netflix.partner.activation\n",
            dynamicProtected = setOf("com.oplus.foo"),
        )
        val byPkg = inv.associateBy { it.info.pkg }
        assertEquals(PackageState.UNINSTALLED_FOR_USER, byPkg["com.netflix.partner.activation"]!!.state)
        assertEquals(PackageState.DISABLED, byPkg["com.oplus.foo"]!!.state)
        assertEquals(DebloatRisk.PROTECTED, byPkg["com.oplus.foo"]!!.info.risk, "dynamic protection (launcher/keyboard)")
        assertEquals(DebloatRisk.PROTECTED, inv.first().info.risk)
        assertEquals(DebloatRisk.RECOMMENDED, DebloatClassifier.classify("com.facebook.appmanager").risk)
        assertEquals(DebloatRisk.UNKNOWN, DebloatClassifier.classify("com.oplus.weather.service").risk)
    }
}

class TweakPlannerAndExecutorTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `animation plan restores previous values or deletes unset keys`() {
        val c = TweakPlanner.animation("0.5", mapOf("window_animation_scale" to "1.0", "transition_animation_scale" to null, "animator_duration_scale" to "garbage"), 1)!!
        assertEquals(3, c.apply.size)
        assertEquals(TweakOp.SettingPut(SettingNamespace.GLOBAL, "window_animation_scale", "1.0"), c.undo[0])
        assertEquals(TweakOp.SettingDelete(SettingNamespace.GLOBAL, "transition_animation_scale"), c.undo[1])
        assertEquals(TweakOp.SettingDelete(SettingNamespace.GLOBAL, "animator_duration_scale"), c.undo[2])
        assertNull(TweakPlanner.animation("3", emptyMap(), 1))
    }

    @Test
    fun `density stays within 25 percent of physical`() {
        val d = TweakParsers.Density(480, null)
        assertNull(TweakPlanner.density(300, d, 1))
        assertNull(TweakPlanner.density(700, d, 1))
        val c = TweakPlanner.density(420, d, 1)!!
        assertEquals(listOf(TweakOp.DensityReset), c.undo)
        assertEquals(listOf<TweakOp>(TweakOp.DensityReset), TweakPlanner.density(480, TweakParsers.Density(480, 400), 1)!!.apply)
        assertEquals(listOf<TweakOp>(TweakOp.DensitySet(400)), TweakPlanner.density(480, TweakParsers.Density(480, 400), 1)!!.undo)
    }

    @Test
    fun `background fix only removes the whitelist entry it added`() {
        val fresh = TweakPlanner.backgroundFix("com.whatsapp", TweakPlanner.BackgroundState(false, "ignore", "default", 40), 1)!!
        assertTrue(TweakOp.IdleWhitelistAdd("com.whatsapp") in fresh.apply)
        assertTrue(TweakOp.IdleWhitelistRemove("com.whatsapp") in fresh.undo)
        assertTrue(TweakOp.AppOpSet("com.whatsapp", "RUN_ANY_IN_BACKGROUND", "ignore") in fresh.undo)
        assertTrue(TweakOp.StandbySet("com.whatsapp", "rare") in fresh.undo)
        val already = TweakPlanner.backgroundFix("com.whatsapp", TweakPlanner.BackgroundState(true, "allow", "allow", 5), 1)!!
        assertFalse(already.undo.any { it is TweakOp.IdleWhitelistRemove })
        assertFalse(already.undo.any { it is TweakOp.StandbySet }, "exempted bucket cannot be set back by name")
    }

    @Test
    fun `debloat refuses protected packages and undo reinstalls`() {
        assertNull(TweakPlanner.debloat("com.android.systemui", uninstall = false, now = 1))
        assertEquals(listOf<TweakOp>(TweakOp.PackageInstallExisting("com.facebook.appmanager")), TweakPlanner.debloat("com.facebook.appmanager", true, 1)!!.undo)
    }

    @Test
    fun `private dns and refresh rate plans`() {
        val dns = TweakPlanner.privateDns("dns.adguard-dns.com", "opportunistic", null, 1)!!
        assertEquals(TweakOp.SettingPut(SettingNamespace.GLOBAL, "private_dns_mode", "hostname"), dns.apply.last())
        assertEquals(TweakOp.SettingDelete(SettingNamespace.GLOBAL, "private_dns_specifier"), dns.undo.first())
        assertNull(TweakPlanner.privateDns("bad host", null, null, 1))
        val rr = TweakPlanner.refreshRate(120f, null, "60.0", 1)!!
        assertEquals("120.0", rr.target)
        assertEquals(TweakOp.SettingPut(SettingNamespace.SYSTEM, "min_refresh_rate", "60.0"), rr.undo[1])
    }

    private class FakeDevice {
        val settings = HashMap<String, String>()
        var failOn: TweakOp? = null
        val log = ArrayList<TweakOp>()
        fun run(op: TweakOp): OpResult {
            log += op
            if (op == failOn) return OpResult(false, 1, "Error: boom")
            when (op) {
                is TweakOp.SettingPut -> settings[op.key] = op.value
                is TweakOp.SettingDelete -> settings.remove(op.key)
                else -> {}
            }
            return OpResult(true, 0, "")
        }
    }

    @Test
    fun `executor applies, journals and reverts to the original state`() = runTest {
        val dev = FakeDevice().apply { settings["window_animation_scale"] = "1.0" }
        val journal = TweakJournal(AtomicTextFile(tmp.resolve("j.json")))
        val ex = TweakExecutor(journal) { dev.run(it) }
        val first = TweakPlanner.animation("0.5", mapOf("window_animation_scale" to "1.0"), 1)!!
        assertIs<TweakExecutor.Outcome.Applied>(ex.apply(first))
        // A second change in the same slot keeps the ORIGINAL undo.
        val second = TweakPlanner.animation("0.0", mapOf("window_animation_scale" to "0.5"), 2)!!
        assertIs<TweakExecutor.Outcome.Applied>(ex.apply(second))
        assertEquals(1, journal.active().size)
        assertEquals(ChangeStatus.SUPERSEDED, journal.all().first().status)
        assertEquals(0, ex.revertAll())
        assertEquals("1.0", dev.settings["window_animation_scale"])
        assertFalse("transition_animation_scale" in dev.settings)
        // Journal survives reopening.
        assertEquals(ChangeStatus.REVERTED, TweakJournal(AtomicTextFile(tmp.resolve("j.json"))).all().last().status)
    }

    @Test
    fun `failure mid-change rolls back`() = runTest {
        val dev = FakeDevice().apply { settings["window_animation_scale"] = "1.0" }
        val journal = TweakJournal(AtomicTextFile(tmp.resolve("j2.json")))
        val change = TweakPlanner.animation("0.5", mapOf("window_animation_scale" to "1.0"), 1)!!
        dev.failOn = change.apply[1]
        val out = TweakExecutor(journal) { dev.run(it) }.apply(change)
        assertIs<TweakExecutor.Outcome.Failed>(out)
        assertTrue(out.rolledBack)
        assertEquals("1.0", dev.settings["window_animation_scale"])
        assertEquals(ChangeStatus.FAILED, journal.all().single().status)
        assertTrue(journal.active().isEmpty())
    }

    @Test
    fun `package restore cancels an earlier debloat`() = runTest {
        val dev = FakeDevice()
        val journal = TweakJournal(AtomicTextFile(tmp.resolve("j3.json")))
        val ex = TweakExecutor(journal) { dev.run(it) }
        ex.apply(TweakPlanner.debloat("com.facebook.appmanager", false, 1)!!)
        ex.apply(TweakPlanner.restorePackage("com.facebook.appmanager", PackageState.DISABLED, 2)!!)
        assertTrue(journal.active().none { it.kind == TweakKind.DEBLOAT_DISABLE })
    }
}

class AppOpsDumpCommandTest {
    @Test
    fun `history op is a fixed read-only dumpsys argv`() {
        val op = TweakOp.AppOpsDump(com.bright.onepluscare.core.privacy.PrivacyOp.MICROPHONE)
        kotlin.test.assertTrue(op.readOnly)
        kotlin.test.assertEquals(listOf("/system/bin/dumpsys", "appops", "--op", "RECORD_AUDIO"), TweakCommands.argvFor(op, "com.bright.onepluscare"))
        kotlin.test.assertEquals(op, TweakOp.decode(TweakOp.encode(op)))
        kotlin.test.assertNull(TweakOp.decode("""{"type":"appops_history","op":"READ_SMS"}"""))
    }
}
