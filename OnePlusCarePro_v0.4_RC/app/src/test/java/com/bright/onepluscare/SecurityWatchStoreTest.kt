package com.bright.onepluscare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bright.onepluscare.core.security.Surface
import com.bright.onepluscare.work.SecurityWatchStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SecurityWatchStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `off by default, baseline reset when turned on, surfaces round-trip`() {
        val s = SecurityWatchStore(ctx)
        assertFalse(s.enabled.value)
        s.sequence = 12
        s.surfaces = mapOf(Surface.ACCESSIBILITY to setOf("a.b.c"))
        assertEquals(setOf("a.b.c"), SecurityWatchStore(ctx).surfaces!![Surface.ACCESSIBILITY])
        s.setEnabled(true)
        assertTrue(SecurityWatchStore(ctx).enabled.value)
        assertNull(SecurityWatchStore(ctx).surfaces)
        assertNull(SecurityWatchStore(ctx).sequence)
        s.markAnnounced(listOf("k1"))
        assertTrue("k1" in SecurityWatchStore(ctx).announced())
    }
}
