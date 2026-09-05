package com.baer.handtype

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.settings.AppSettingsStore
import com.baer.handtype.feature.settings.localizedFor
import com.baer.handtype.testing.HandTypeDebugHooks
import com.baer.handtype.testing.HandTypeTestTags
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PremiumPaywallTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tappingPremiumButtonOpensPaywallAndFakePurchaseUnlocksCapture() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val context = baseContext.localizedFor(AppSettingsStore(baseContext).read().languagePreference)

        composeRule.onNodeWithText(context.getString(R.string.template_chooser_premium_button))
            .performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(HandTypeTestTags.PREMIUM_PAYWALL_SHEET).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(HandTypeTestTags.PREMIUM_PAYWALL_SHEET).assertIsDisplayed()

        composeRule.onNodeWithTag(HandTypeTestTags.PREMIUM_BUY_BUTTON).performClick()

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(HandTypeTestTags.CAMERA_SCAN_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(HandTypeTestTags.CAMERA_SCAN_BUTTON).assertIsDisplayed()
    }

    companion object {
        private const val TIMEOUT_MS = 25_000L
        private const val PREFS_NAME = "handtype_app_settings"
        private const val KEY_PREMIUM_UNLOCKED = "premium_unlocked"
        private var previousPremiumUnlocked = false

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val targetContext = ApplicationProvider.getApplicationContext<Context>()
            val prefs = targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            previousPremiumUnlocked = prefs.getBoolean(KEY_PREMIUM_UNLOCKED, false)
            HandTypeDebugHooks.premiumUnlocked = false
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            HandTypeDebugHooks.clear()
            val targetContext = ApplicationProvider.getApplicationContext<Context>()
            targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PREMIUM_UNLOCKED, previousPremiumUnlocked)
                .apply()
        }
    }
}
