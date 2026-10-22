package com.bright.onepluscare

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.bright.onepluscare.core.rules.Evidence
import com.bright.onepluscare.core.rules.EvidenceKind
import com.bright.onepluscare.core.rules.Recommendation
import com.bright.onepluscare.core.rules.RecommendationId
import com.bright.onepluscare.core.rules.RequiredPermission
import com.bright.onepluscare.core.rules.Reversibility
import com.bright.onepluscare.core.rules.RiskLevel
import com.bright.onepluscare.core.rules.Severity
import com.bright.onepluscare.core.rules.UserAction
import com.bright.onepluscare.ui.components.ConfirmDialog
import com.bright.onepluscare.ui.components.StatusPill
import com.bright.onepluscare.ui.components.Tone
import com.bright.onepluscare.ui.nav.CareApp
import com.bright.onepluscare.ui.screens.RecommendationCard
import com.bright.onepluscare.ui.theme.CareTheme
import com.bright.onepluscare.ui.vm.LocalAppGraph
import com.bright.onepluscare.ui.vm.LocalViewModelFactory
import com.bright.onepluscare.ui.vm.careViewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareApplication::class)
class ComposeScreensTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun `status pill shows text, not only color`() {
        compose.setContent { CareTheme { StatusPill(Tone.CRITICAL, "Critical") } }
        compose.onNodeWithText("Critical").assertIsDisplayed()
    }

    @Test
    fun `recommendation card reveals evidence, risk and reversibility and fires its action`() {
        var fired: UserAction? = null
        val rec = Recommendation(
            RecommendationId.TRASH_HOLDING_SPACE, Severity.INFO,
            listOf(Evidence.Bytes(EvidenceKind.TRASH_BYTES, 2_000_000_000)),
            2_000_000_000, RiskLevel.MEDIUM, Reversibility.IRREVERSIBLE, RequiredPermission.NONE, UserAction.OPEN_TRASH,
        )
        compose.setContent { CareTheme { RecommendationCard(rec, onAction = { fired = it }) } }
        compose.onNodeWithText(s(R.string.rec_trash_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.rec_field_risk)).assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasContentDescription(s(R.string.action_show_details))).performClick()
        compose.onNodeWithText(s(R.string.rec_field_risk)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.risk_medium)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.rec_rule_based_note)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_open_trash)).performClick()
        assertEquals(UserAction.OPEN_TRASH, fired)
    }

    @Test
    fun `irreversible confirmation states that it cannot be undone`() {
        var confirmed = false
        compose.setContent {
            CareTheme { ConfirmDialog("Delete?", "Body", "Delete", irreversible = true, onConfirm = { confirmed = true }, onDismiss = {}) }
        }
        compose.onNodeWithText(s(R.string.confirm_cannot_undo)).assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun `onboarding can be skipped and lands on the dashboard`() {
        val graph = (context as CareApplication).graph
        compose.setContent {
            CompositionLocalProvider(LocalAppGraph provides graph, LocalViewModelFactory provides careViewModelFactory(graph)) {
                CareTheme { CareApp() }
            }
        }
        compose.onNodeWithText(s(R.string.onb_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_continue)).performClick()
        compose.onNodeWithText(s(R.string.onb_caps_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_skip)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(s(R.string.action_run_smart_scan))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(s(R.string.tab_tools)).performClick()
        compose.onNodeWithText(s(R.string.screen_security)).assertIsDisplayed().performClick()
        compose.onNodeWithText(s(R.string.sec_intro_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.sec_scan)).assertIsDisplayed()
    }
}
