package com.baer.handtype

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.settings.AppSettingsStore
import com.baer.handtype.feature.settings.localizedFor
import com.baer.handtype.testing.HandTypeDebugHooks
import com.baer.handtype.testing.HandTypeTestTags
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A purchase completed outside the paywall (pending payment, promo code) must unlock on the next resume. */
@RunWith(AndroidJUnit4::class)
class PremiumRefreshOnResumeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun purchaseCompletedWhileAwayUnlocksCaptureAfterResume() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val context = baseContext.localizedFor(AppSettingsStore(baseContext).read().languagePreference)

        HandTypeDebugHooks.premiumUnlocked = true
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.template_chooser_premium_button))
            .performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(HandTypeTestTags.CAMERA_SCAN_BUTTON).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag(HandTypeTestTags.PREMIUM_PAYWALL_SHEET).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "paywall opened although the purchase completed before resume",
            composeRule.onAllNodesWithTag(HandTypeTestTags.PREMIUM_PAYWALL_SHEET).fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithTag(HandTypeTestTags.CAMERA_SCAN_BUTTON).assertIsDisplayed()
    }

    companion object {
        private const val TIMEOUT_MS = 25_000L
        private const val PREFS_NAME = "handtype_app_settings"
        private const val KEY_PREMIUM_UNLOCKED = "premium_unlocked"
        private const val KEY_ONBOARDING = "onboarding_complete"
        private var previousPremiumUnlocked = false
        private var previousOnboarding = false

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val prefs = ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            previousPremiumUnlocked = prefs.getBoolean(KEY_PREMIUM_UNLOCKED, false)
            previousOnboarding = prefs.getBoolean(KEY_ONBOARDING, false)
            prefs.edit().putBoolean(KEY_ONBOARDING, true).commit()
            HandTypeDebugHooks.premiumUnlocked = false
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            HandTypeDebugHooks.clear()
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PREMIUM_UNLOCKED, previousPremiumUnlocked)
                .putBoolean(KEY_ONBOARDING, previousOnboarding)
                .apply()
        }
    }
}
