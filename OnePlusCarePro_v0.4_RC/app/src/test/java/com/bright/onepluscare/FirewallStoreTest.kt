package com.bright.onepluscare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bright.onepluscare.core.security.NetKind
import com.bright.onepluscare.firewall.FirewallStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FirewallStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `rc3 single list migrates to both networks once`() {
        ctx.getSharedPreferences("care_firewall", Context.MODE_PRIVATE).edit().putStringSet("blocked", setOf("com.a.b", "bad name")).commit()
        val store = FirewallStore(ctx)
        assertEquals(setOf("com.a.b"), store.current().blockWifi)
        assertEquals(setOf("com.a.b"), store.current().blockMobile)
        store.set("com.a.b", NetKind.MOBILE, false)
        assertEquals(emptySet<String>(), FirewallStore(ctx).current().blockMobile)
        assertEquals(setOf("com.a.b"), FirewallStore(ctx).current().blockWifi)
    }

    @Test
    fun `this app can never be blocked`() {
        val store = FirewallStore(ctx)
        store.setEverywhere(ctx.packageName, true)
        assertTrue(store.current().isEmpty)
    }
}
