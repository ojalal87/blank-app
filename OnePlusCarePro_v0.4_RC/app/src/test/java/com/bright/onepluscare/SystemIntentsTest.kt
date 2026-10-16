package com.bright.onepluscare

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.bright.onepluscare.data.SystemIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SystemIntentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val intents = SystemIntents(context)

    @Test
    fun `only documented public settings actions are used`() {
        SystemIntents.Target.entries.filter { it != SystemIntents.Target.SHIZUKU_APP }.forEach { t ->
            val i = intents.intentFor(t)
            assertNotNull("$t", i)
            // No explicit component: never an OEM-private activity.
            assertEquals(null, i!!.component)
            val action = i.action!!
            assert(action.startsWith("android.settings.") || action.startsWith("android.intent.action.")) { action }
        }
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intents.intentFor(SystemIntents.Target.USAGE_ACCESS)!!.action)
    }

    @Test
    fun `app details refuses malformed package names`() {
        listOf("", "a", "../x", "com.example;rm", "com example.app", "package:com.x").forEach {
            assertFalse(it, intents.openAppDetails(it))
        }
    }
}
