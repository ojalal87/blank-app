package com.rootguard.app

import androidx.test.core.app.ApplicationProvider
import com.rootguard.app.data.FirewallController
import com.rootguard.app.data.InstalledPackage
import com.rootguard.app.data.InventoryRepository
import com.rootguard.app.data.db.ApplyTransactionEntity
import com.rootguard.app.data.db.ControlDatabase
import com.rootguard.app.root.PrivilegedGateway
import com.rootguard.app.root.RootState
import com.rootguard.core.FakeIptables
import com.rootguard.core.FakeSys
import com.rootguard.core.MemJournal
import com.rootguard.core.helper.HelperEngine
import com.rootguard.core.model.EnforcementHealth
import com.rootguard.core.model.Mode
import com.rootguard.core.model.NetworkScope
import com.rootguard.core.model.Phase
import com.rootguard.core.protocol.ErrorCode
import com.rootguard.core.protocol.Operation
import com.rootguard.core.protocol.Payload
import com.rootguard.core.protocol.Request
import com.rootguard.core.protocol.Response
import com.rootguard.core.protocol.decodeResponse
import com.rootguard.core.protocol.encode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Gateway backed by the real HelperEngine over the in-memory iptables model: the whole M1 slice off-device. */
class FakeGateway(var ipt: FakeIptables, val journal: MemJournal = MemJournal(), var root: Boolean = true) : PrivilegedGateway {
    var engine = HelperEngine(ipt, FakeSys(), journal, "boot-1")
    private var now = 1_000L
    override val state: StateFlow<RootState> = MutableStateFlow(RootState.CONNECTED)
    override suspend fun connect() = root
    override suspend fun request(op: Operation, payload: Payload, expectedRevision: String?): Response {
        now += 10
        return decodeResponse(engine.handle(Request(1, UUID.randomUUID().toString(), op, "boot-1", now + 30_000, expectedRevision, payload).encode(), now))!!
    }
    /** Simulates a reboot: kernel rules gone, new helper process; the helper journal file survives. */
    fun reboot() {
        ipt = FakeIptables()
        engine = HelperEngine(ipt, FakeSys(), journal, "boot-1")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FirewallControllerTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = ControlDatabase.build(ctx, name = null)
    private val gw = FakeGateway(FakeIptables())
    private val c = FirewallController(db, gw, InventoryRepository(ctx, db))

    @After fun close() = db.close()

    private fun pkg(name: String, uid: Int, installed: Long = 1) =
        InstalledPackage(name, name.substringAfterLast('.'), uid, installed, 1, "lineage-$name", "signer", false, true, null)

    private fun setup() = runBlocking {
        c.load()
        c.recordConsent()
        c.probe()
        c.applyInventory(listOf(pkg("org.example.browser", 10123), pkg("com.vendor.a", 10200), pkg("com.vendor.b", 10200)))
    }

    private fun principalOf(uid: Int) = c.principals.value.first { it.uid == uid }.principalId

    @Test
    fun `apply verifies, commits and records an auditable revision`() = runBlocking {
        setup()
        assertEquals(com.rootguard.core.model.Attribution.SHARED, c.principals.value.first { it.uid == 10200 }.attribution)
        c.setAllowed(principalOf(10123), NetworkScope.WIFI, false)
        val r = c.apply()!!
        assertEquals(Phase.VERIFIED, r.phase)
        val s = c.state.value
        assertEquals(EnforcementHealth.ACTIVE, s.health)
        val rt = db.policy().runtime()!!
        assertEquals(s.appliedRevision, rt.appliedRevision)
        assertEquals(r.verifiedHash, rt.verifiedHash)
        assertTrue(gw.ipt.tables.getValue(4).chains["RG_OUT"]!!.any { it.contains("--uid-owner 10123") && it.contains("wlan+") })
        assertEquals(listOf("-j RG_OUT", "-j fw_OUTPUT", "-j bw_OUTPUT"), gw.ipt.tables.getValue(6).output)
        assertNull(db.policy().openTransaction())
    }

    @Test
    fun `failed apply is compensated and the last good revision stays active`() = runBlocking {
        setup()
        c.setAllowed(principalOf(10123), NetworkScope.WIFI, false)
        c.apply()
        val good = c.state.value.appliedRevision
        gw.ipt.failRestore = 6
        c.setAllowed(principalOf(10200), NetworkScope.MOBILE, false)
        val r = c.apply()!!
        assertEquals(Phase.ROLLED_BACK, r.phase)
        assertTrue(r.compensated)
        gw.ipt.failRestore = null
        assertEquals(good, c.state.value.appliedRevision)
        assertEquals(EnforcementHealth.ACTIVE, c.state.value.health)
        assertEquals(ErrorCode.VERIFY_FAILED, c.state.value.lastError)
    }

    @Test
    fun `reboot clears kernel rules - reported OFF, then re-applied from last good`() = runBlocking {
        setup()
        c.setAllowed(principalOf(10123), NetworkScope.MOBILE, false)
        c.apply()
        gw.reboot()
        val fresh = FirewallController(db, gw, InventoryRepository(ctx, db))
        fresh.load()
        fresh.reconcile()
        assertEquals(EnforcementHealth.OFF, fresh.state.value.health)
        assertEquals("KERNEL_RULES_ABSENT", fresh.state.value.reason)
        assertTrue(fresh.reapplyLastGood())
        assertEquals(EnforcementHealth.ACTIVE, fresh.state.value.health)
    }

    @Test
    fun `crash after the kernel switch but before the DB commit is completed on reconcile`() = runBlocking {
        setup()
        c.setAllowed(principalOf(10123), NetworkScope.WIFI, false)
        c.apply()
        val rev = c.state.value.appliedRevision!!
        // Pretend the DB never saw the commit: reopen the transaction and roll runtime back.
        db.openHelper.writableDatabase.execSQL("UPDATE apply_transaction SET phase='APPLYING'")
        db.openHelper.writableDatabase.execSQL("UPDATE runtime_state SET applied_revision=NULL")
        val fresh = FirewallController(db, gw, InventoryRepository(ctx, db))
        fresh.load()
        fresh.reconcile()
        assertEquals(EnforcementHealth.ACTIVE, fresh.state.value.health)
        assertEquals(rev, db.policy().runtime()!!.appliedRevision)
        assertNull(db.policy().openTransaction())
    }

    @Test
    fun `recovery removes own rules only`() = runBlocking {
        setup()
        c.setMode(Mode.STRICT)
        c.apply()
        assertTrue(c.recover())
        assertEquals(EnforcementHealth.OFF, c.state.value.health)
        gw.ipt.tables.values.forEach { t ->
            assertFalse("RG_OUT" in t.chains)
            assertEquals(listOf("-j fw_OUTPUT", "-j bw_OUTPUT"), t.output)
        }
    }

    @Test
    fun `uid reuse creates a new principal and old rules do not follow`() = runBlocking {
        setup()
        val old = principalOf(10123)
        c.setAllowed(old, NetworkScope.WIFI, false)
        c.applyInventory(listOf(pkg("com.other.app", 10123, installed = 50), pkg("com.vendor.a", 10200), pkg("com.vendor.b", 10200)))
        val now = principalOf(10123)
        assertNotEquals(old, now)
        assertTrue(c.draft.value.rules(c.principals.value).none { it.principalId == now })
    }

    @Test
    fun `root denied is an honest error, nothing applied`() = runBlocking {
        gw.root = false
        c.load()
        assertNull(c.probe())
        assertEquals(ErrorCode.ROOT_DENIED, c.state.value.lastError)
        assertNull(c.apply())
        assertTrue(gw.ipt.calls.isEmpty())
    }
}
