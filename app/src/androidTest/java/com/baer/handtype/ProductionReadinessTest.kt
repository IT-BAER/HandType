package com.baer.handtype

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.baer.handtype.feature.capture.CameraCaptureScreen
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.template.HandwritingRenderScreen
import com.baer.handtype.feature.template.TemplateChooserScreen
import com.baer.handtype.template.BundledTemplateRepository
import com.baer.handtype.testing.HandTypeTestTags
import com.baer.handtype.ui.theme.HandTypeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionReadinessTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun largeTextKeepsScanActionVisible() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                HandTypeTheme {
                    CameraCaptureScreen(onImageCaptured = {}, onCaptureError = {})
                }
            }
        }
        rule.onNodeWithTag(HandTypeTestTags.CAMERA_SCAN_BUTTON).assertIsDisplayed()
    }

    @Test fun templateCanBeSelectedWithAccessibilityAction() {
        val repo = BundledTemplateRepository(context)
        var selected = false
        rule.setContent {
            HandTypeTheme {
                TemplateChooserScreen(
                    templates = repo.listBuiltInTemplates(),
                    premiumTemplate = repo.premiumTemplate(),
                    onTemplateSelected = { selected = true },
                    onPremiumSelected = {},
                )
            }
        }
        rule.onAllNodesWithText(context.getString(R.string.template_chooser_use_template))[0]
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertTrue("Template CTA must expose an accessibility click", selected) }
    }

    @Test fun systemBackFromResultRestoresDraft() {
        val template = runBlocking { BundledTemplateRepository(context).loadTemplate("classic_script") }
        var leftEditor = false
        rule.setContent {
            HandTypeTheme {
                BackHandler { leftEditor = true }
                HandwritingRenderScreen(template, { leftEditor = true }, {})
            }
        }
        rule.onNodeWithTag(HandTypeTestTags.RENDER_GENERATE_BUTTON).assertIsNotEnabled()
        rule.onNodeWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT)
            .performTextReplacement("Keep this draft.")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag(HandTypeTestTags.RENDER_GENERATE_BUTTON).performScrollTo().performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag(HandTypeTestTags.RENDER_RESULT_IMAGE).fetchSemanticsNodes().isNotEmpty()
        }
        Espresso.pressBack()
        rule.waitForIdle()
        rule.runOnIdle { assertFalse("Back must edit the result instead of leaving the editor", leftEditor) }
        rule.onNodeWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT).assertTextContains("Keep this draft.")
    }
}
