package com.rootguard.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.rootguard.app.data.FirewallController
import com.rootguard.app.data.InventoryRepository
import com.rootguard.app.data.db.ControlDatabase
import com.rootguard.app.ui.MainViewModel
import com.rootguard.app.ui.RgTheme
import com.rootguard.app.ui.RootGuardRoot
import com.rootguard.core.FakeIptables
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = ctx.getString(id)

    @Test
    fun `welcome leads to consent, which cannot be accepted without the explicit checkbox`() {
        val db = ControlDatabase.build(ctx, name = null)
        val vm = MainViewModel(FirewallController(db, FakeGateway(FakeIptables()), InventoryRepository(ctx, db)))
        compose.setContent { RgTheme { RootGuardRoot(vm) } }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(s(R.string.welcome_title))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(s(R.string.action_continue)).performClick()
        compose.onNodeWithText(s(R.string.consent_helper_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.consent_accept)).assertIsNotEnabled()
    }
}
