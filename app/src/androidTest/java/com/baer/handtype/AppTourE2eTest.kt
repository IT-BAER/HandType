package com.baer.handtype

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.feature.settings.AppSettings
import com.baer.handtype.feature.settings.AppSettingsStore
import com.baer.handtype.feature.settings.LanguagePreference
import com.baer.handtype.feature.settings.ThemePreference
import com.baer.handtype.feature.settings.localizedFor
import com.baer.handtype.feature.settings.nativeLanguageName
import com.baer.handtype.testing.HandTypeDebugHooks
import com.baer.handtype.testing.HandTypeTestTags
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Settings (theme, language, premium, support rows), the paywall, the share sheets and the
 * Help/FAQ/About/Privacy screens in the real app. A screenshot of every step lands in the app's
 * external files dir under handtype_test/tour for review.
 */
@RunWith(AndroidJUnit4::class)
class AppTourE2eTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val baseContext = ApplicationProvider.getApplicationContext<Context>()

    // Re-read on every use: the language step recreates the activity in another language.
    private val context: Context
        get() = baseContext.localizedFor(AppSettingsStore(baseContext).read().languagePreference)

    @Test
    fun settingsPaywallShareAndInfoScreens() {
        dismissOnboarding()
        shot("chooser")

        openDrawer()
        shot("drawer")
        composeRule.onAllNodesWithText(context.getString(R.string.drawer_nav_settings))[0].performClick()
        composeRule.waitForIdle()
        shot("settings")

        for (theme in listOf(ThemePreference.Dark, ThemePreference.Light, original.themePreference)) {
            composeRule.onNodeWithTag(HandTypeTestTags.settingsThemeOption(theme.name)).performClick()
            composeRule.waitForIdle()
            assertEquals(theme, AppSettingsStore(baseContext).read().themePreference)
            shot("settings_theme_${theme.name.lowercase()}")
        }

        for (language in listOf(LanguagePreference.English, original.languagePreference)) {
            composeRule.onNodeWithTag(HandTypeTestTags.settingsLanguageOption(language.name)).performScrollTo().performClick()
            composeRule.waitForIdle()
            assertEquals(language, AppSettingsStore(baseContext).read().languagePreference)
            // The recreated activity keeps the route and shows Settings in the new language.
            composeRule.onNodeWithText(context.getString(R.string.settings_language_title)).assertExists()
            shot("settings_language_${language.name.lowercase()}")
        }

        composeRule.onNodeWithText(context.getString(R.string.template_chooser_premium_button)).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasTestTag(HandTypeTestTags.PREMIUM_BUY_BUTTON) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        shot("paywall")
        composeRule.onNodeWithTag(HandTypeTestTags.PREMIUM_RESTORE_BUTTON).performClick()
        waitForToast()
        shot("paywall_restore_nothing_owned")
        composeRule.onNodeWithTag(HandTypeTestTags.PREMIUM_BUY_BUTTON).performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(context.getString(R.string.settings_premium_unlocked)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(HandTypeTestTags.PREMIUM_PAYWALL_SHEET).fetchSemanticsNodes().isEmpty()
        }
        shot("settings_premium_unlocked")
        // From here the fake billing follows the purchase it stored instead of the forced lock.
        HandTypeDebugHooks.premiumUnlocked = null
        // Toasts queue; let the paywall's one expire so the screenshot shows the restore result.
        Thread.sleep(2_500)
        composeRule.onNodeWithText(context.getString(R.string.premium_paywall_restore)).performScrollTo().performClick()
        waitForToast()
        shot("settings_restore_success")

        composeRule.onNodeWithTag(HandTypeTestTags.SETTINGS_SHARE_APP).performScrollTo().performClick()
        leaveAndComeBack("share_app_chooser")
        composeRule.onNodeWithTag(HandTypeTestTags.SETTINGS_RATE_APP).performScrollTo().performClick()
        leaveAndComeBack("rate_app_store")

        for ((label, name) in listOf(
            R.string.drawer_nav_help to "help",
            R.string.drawer_nav_faq to "faq",
            R.string.drawer_nav_about to "about",
            R.string.drawer_nav_privacy to "privacy",
        )) {
            openDrawer()
            composeRule.onAllNodesWithText(context.getString(label))[0].performClick()
            composeRule.waitForIdle()
            // The drawer's close animation runs on the real clock.
            Thread.sleep(500)
            shot(name)
            // Inside the content: a swipe from the root's bottom edge lands on the navigation bar inset.
            repeat(4) { composeRule.onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f) } }
            shot("${name}_end")
        }

        openDrawer()
        composeRule.onAllNodesWithText(context.getString(R.string.drawer_nav_templates))[0].performClick()
        composeRule.waitForIdle()
        useTemplateOnScreen()
        composeRule.onNodeWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT).performTextReplacement("Bis bald!")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag(HandTypeTestTags.RENDER_GENERATE_BUTTON).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(HandTypeTestTags.RENDER_RESULT_IMAGE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        shot("result")
        composeRule.onNodeWithText(context.getString(R.string.result_share_button)).performClick()
        leaveAndComeBack("result_share_chooser")
        composeRule.onNodeWithTag(HandTypeTestTags.RENDER_RESULT_IMAGE).assertIsDisplayed()
    }

    private fun themeLabel(theme: ThemePreference) = context.getString(
        when (theme) {
            ThemePreference.System -> R.string.settings_theme_system
            ThemePreference.Light -> R.string.settings_theme_light
            ThemePreference.Dark -> R.string.settings_theme_dark
        },
    )

    private fun languageLabel(language: LanguagePreference) =
        language.languageTag?.let(::nativeLanguageName) ?: context.getString(R.string.settings_language_system)

    private fun openDrawer() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_menu)).performClick()
        composeRule.waitForIdle()
    }

    /** Screenshots the system window another app opened (share chooser, store), then goes back. */
    private fun leaveAndComeBack(name: String) {
        Thread.sleep(2_000)
        shot(name)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // The Play Store can swallow the first back while its page loads.
        for (attempt in 1..3) {
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(1_500)
            if (composeRule.activity.hasWindowFocus()) break
        }
        composeRule.waitForIdle()
        assertTrue("$name: back returns to HandType", composeRule.activity.hasWindowFocus())
        shot("${name}_back")
    }

    private fun dismissOnboarding() {
        composeRule.waitForIdle()
        repeat(3) {
            if (composeRule.onAllNodesWithTag(HandTypeTestTags.ONBOARDING_NEXT).fetchSemanticsNodes().isEmpty()) return
            composeRule.onNodeWithTag(HandTypeTestTags.ONBOARDING_NEXT).performClick()
            composeRule.waitForIdle()
        }
    }

    /** Taps "use template" on the card inside the pager, not on a peeking neighbour. */
    private fun useTemplateOnScreen() {
        val buttons = composeRule.onAllNodesWithText(context.getString(R.string.template_chooser_use_template))
        buttons[buttons.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.width >= it.size.width - 1f }].performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForToast() {
        composeRule.waitForIdle()
        Thread.sleep(600)
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = baseContext.getExternalFilesDir(null)!!.resolve("handtype_test/tour").apply { mkdirs() }
        dir.resolve("%02d_%s.png".format(frame++, name)).outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        private const val TIMEOUT_MS = 25_000L
        private var frame = 0
        private var original = AppSettings()
        private var existingHistoryFiles = emptySet<String>()

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val target = ApplicationProvider.getApplicationContext<Context>()
            target.getExternalFilesDir(null)!!.resolve("handtype_test/tour").deleteRecursively()
            original = AppSettingsStore(target).read()
            existingHistoryFiles = File(target.filesDir, "history").list().orEmpty().toSet()
            AppSettingsStore(target).write(original.copy(premiumUnlocked = false))
            HandTypeDebugHooks.premiumUnlocked = false
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            HandTypeDebugHooks.clear()
            val target = ApplicationProvider.getApplicationContext<Context>()
            AppSettingsStore(target).write(original)
            File(target.filesDir, "history").listFiles().orEmpty()
                .filterNot { it.name in existingHistoryFiles }.forEach { it.delete() }
        }
    }
}
