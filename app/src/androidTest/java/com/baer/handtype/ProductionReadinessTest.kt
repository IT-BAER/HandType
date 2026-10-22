package com.baer.handtype

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.baer.handtype.feature.capture.CameraCaptureScreen
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.onboarding.OnboardingSheet
import com.baer.handtype.feature.settings.AppSettings
import com.baer.handtype.feature.settings.SettingsScreen
import com.baer.handtype.feature.template.HandwritingRenderScreen
import com.baer.handtype.feature.template.TemplateChooserScreen
import com.baer.handtype.feature.template.TextSizeScale
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
                    CameraCaptureScreen(
                        onImageCaptured = {},
                        onCaptureError = {},
                        instructionText = context.getString(R.string.premium_instruction),
                    )
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

    @Test fun settingsShowsShareAndRateRows() {
        rule.setContent {
            HandTypeTheme {
                SettingsScreen(
                    settings = AppSettings(),
                    onSettingsChanged = {},
                    onOpenDrawer = {},
                    premiumUnlocked = true,
                    onUnlockPremium = {},
                    onRestorePremium = {},
                    onShareApp = {},
                    onRateApp = {},
                )
            }
        }
        rule.onNodeWithTag(HandTypeTestTags.SETTINGS_SHARE_APP).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(HandTypeTestTags.SETTINGS_RATE_APP).performScrollTo().assertIsDisplayed()
    }

    @Test fun onboardingWalksThreePagesThenRevealsChooser() {
        val repo = BundledTemplateRepository(context)
        rule.setContent {
            HandTypeTheme {
                var complete by remember { mutableStateOf(false) }
                TemplateChooserScreen(
                    templates = repo.listBuiltInTemplates(),
                    premiumTemplate = repo.premiumTemplate(),
                    onTemplateSelected = {},
                    onPremiumSelected = {},
                )
                if (!complete) OnboardingSheet(onFinished = { complete = true })
            }
        }
        rule.onNodeWithTag(HandTypeTestTags.ONBOARDING_SHEET).assertIsDisplayed()
        repeat(3) {
            rule.onNodeWithTag(HandTypeTestTags.ONBOARDING_NEXT).performClick()
            rule.waitForIdle()
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(HandTypeTestTags.ONBOARDING_SHEET).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag(HandTypeTestTags.TEMPLATE_PAGER).assertIsDisplayed()
    }

    @Test fun chooserMenuButtonStaysVisibleWhenScrolled() {
        val repo = BundledTemplateRepository(context)
        rule.setContent {
            HandTypeTheme {
                Box(Modifier.height(420.dp)) {
                    TemplateChooserScreen(
                        templates = repo.listBuiltInTemplates(),
                        premiumTemplate = repo.premiumTemplate(),
                        onTemplateSelected = {},
                        onPremiumSelected = {},
                    )
                }
            }
        }
        rule.onNodeWithText(context.getString(R.string.template_chooser_premium_button)).performScrollTo()
        val menu = rule.onNodeWithContentDescription(context.getString(R.string.cd_menu)).fetchSemanticsNode()
        assertTrue(
            "Menu button clipped: visible ${menu.boundsInRoot.height} of ${menu.size.height} px",
            menu.boundsInRoot.height == menu.size.height.toFloat(),
        )
    }

    @Test fun onboardingNextButtonKeepsItsPositionOnEveryPage() {
        rule.setContent { HandTypeTheme { OnboardingSheet(onFinished = {}) } }
        val next = rule.onNodeWithTag(HandTypeTestTags.ONBOARDING_NEXT)
        val tops = (0 until 3).map { page ->
            if (page > 0) next.performClick()
            rule.waitForIdle()
            next.getUnclippedBoundsInRoot().top
        }
        assertTrue("Next button moved between pages: $tops", tops.distinct().size == 1)
    }

    @Test fun systemBackFromResultRestoresDraft() {
        val template = runBlocking { BundledTemplateRepository(context).loadTemplate("classic_script") }
        var leftEditor = false
        rule.setContent {
            HandTypeTheme {
                BackHandler { leftEditor = true }
                HandwritingRenderScreen(
                    template = template,
                    onBackToTemplates = { leftEditor = true },
                    onPremiumSelected = {},
                    premiumUnlocked = true,
                    onLockedPresetTapped = {},
                )
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

    @Test fun generatingCardStaysForOnePenPassEvenWhenRenderIsFaster() {
        val template = runBlocking { BundledTemplateRepository(context).loadTemplate("classic_script") }
        rule.setContent {
            HandTypeTheme {
                HandwritingRenderScreen(
                    template = template,
                    onBackToTemplates = {},
                    onPremiumSelected = {},
                    premiumUnlocked = true,
                    onLockedPresetTapped = {},
                )
            }
        }
        rule.onNodeWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT).performTextReplacement("Hi")
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        rule.onNodeWithTag(HandTypeTestTags.RENDER_GENERATE_BUTTON).performScrollTo()
        rule.mainClock.autoAdvance = false
        // A tap gesture is not processed while the clock is paused; the semantics click is.
        rule.onNodeWithTag(HandTypeTestTags.RENDER_GENERATE_BUTTON)
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeByFrame()
        // Real time for the render on Dispatchers.Default; the test clock stays near 0 ms.
        Thread.sleep(1500)
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText(context.getString(R.string.render_generating_title)).assertExists()
        rule.onNodeWithTag(HandTypeTestTags.RENDER_RESULT_IMAGE).assertDoesNotExist()

        rule.mainClock.advanceTimeBy(1300)
        rule.onNodeWithTag(HandTypeTestTags.RENDER_RESULT_IMAGE).assertExists()
        rule.mainClock.autoAdvance = true
    }

    @Test fun textSizeSliderRerendersTheNoteLargerAndRegenerateKeepsIt() {
        val template = runBlocking { BundledTemplateRepository(context).loadTemplate("classic_script") }
        rule.setContent {
            HandTypeTheme {
                HandwritingRenderScreen(
                    template = template,
                    onBackToTemplates = {},
                    onPremiumSelected = {},
                    premiumUnlocked = true,
                    onLockedPresetTapped = {},
                )
            }
        }
        rule.onNodeWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT)
            .performTextReplacement("Dear Anna,\nthank you for the lovely evening.\nSee you on Sunday!")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag(HandTypeTestTags.RENDER_GENERATE_BUTTON).performScrollTo().performClick()
        val defaultRatio = waitForResultRatio { it > 0f }

        rule.onNodeWithTag(HandTypeTestTags.RESULT_TEXT_SIZE_SLIDER)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(TextSizeScale.MAX) }
        val largeRatio = waitForResultRatio { it > defaultRatio * 1.2f }

        rule.onNodeWithText(context.getString(R.string.result_regenerate_button)).performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText(context.getString(R.string.render_generating_title))
                .fetchSemanticsNodes().isEmpty()
        }
        val regeneratedRatio = waitForResultRatio { it > 0f }
        assertTrue(
            "Regenerate dropped the text size: default=$defaultRatio large=$largeRatio regenerated=$regeneratedRatio",
            regeneratedRatio > defaultRatio * 1.2f,
        )
    }

    /** Height/width of the result preview; it follows the bitmap's aspect ratio. */
    private fun waitForResultRatio(accept: (Float) -> Boolean): Float {
        var ratio = 0f
        rule.waitUntil(15_000) {
            val nodes = rule.onAllNodesWithTag(HandTypeTestTags.RENDER_RESULT_IMAGE).fetchSemanticsNodes()
            ratio = nodes.firstOrNull()?.size?.let { it.height.toFloat() / it.width } ?: 0f
            accept(ratio)
        }
        return ratio
    }

    @Test fun paperCardsAnnounceTheirNameAndLockedState() {
        val template = runBlocking { BundledTemplateRepository(context).loadTemplate("classic_script") }
        rule.setContent {
            HandTypeTheme {
                HandwritingRenderScreen(
                    template = template,
                    onBackToTemplates = {},
                    onPremiumSelected = {},
                    premiumUnlocked = false,
                    onLockedPresetTapped = {},
                )
            }
        }
        val ruled = com.baer.handtype.template.NoteBackgroundCatalog.presetOrDefault("ruled_sheet")
        rule.onNodeWithTag(HandTypeTestTags.backgroundPresetCard("ruled_sheet"))
            .assert(hasContentDescription(context.getString(ruled.labelRes)))
            .assert(hasStateDescription(context.getString(R.string.template_chooser_premium_badge)))
    }

    @Test fun resultShowsEveryActionWithoutScrollingForALongNote() {
        val template = runBlocking { BundledTemplateRepository(context).loadTemplate("classic_script") }
        rule.setContent {
            HandTypeTheme {
                HandwritingRenderScreen(
                    template = template,
                    onBackToTemplates = {},
                    onPremiumSelected = {},
                    premiumUnlocked = true,
                    onLockedPresetTapped = {},
                )
            }
        }
        rule.onNodeWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT)
            .performTextReplacement("Dear Anna,\nthank you for the lovely evening.\nSee you on Sunday!\nLove, Ben")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag(HandTypeTestTags.RENDER_GENERATE_BUTTON).performScrollTo().performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag(HandTypeTestTags.RENDER_RESULT_IMAGE).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        val rootHeight = rule.onRoot().fetchSemanticsNode().size.height
        listOf(R.string.result_save_button, R.string.result_regenerate_button, R.string.result_new_note_button)
            .forEach { res ->
                val node = rule.onNodeWithText(context.getString(res)).fetchSemanticsNode()
                assertTrue(
                    "${context.getString(res)} not fully on screen: ${node.boundsInRoot} root=$rootHeight",
                    node.boundsInRoot.height == node.size.height.toFloat() &&
                        node.boundsInRoot.bottom <= rootHeight,
                )
            }
    }
}
