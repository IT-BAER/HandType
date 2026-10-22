package com.baer.handtype

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.settings.AppSettingsStore
import com.baer.handtype.feature.settings.localizedFor
import com.baer.handtype.testing.HandTypeTestTags
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Text shared from another app lands on the style chooser and prefills the chosen editor. */
@RunWith(AndroidJUnit4::class)
class SharedTextIntakeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun sharedTextPrefillsEditorAfterChoosingAStyle() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val context = baseContext.localizedFor(AppSettingsStore(baseContext).read().languagePreference)
        val shared = "Shared from another app"
        val intent = Intent(baseContext, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "  $shared\n")

        ActivityScenario.launch<MainActivity>(intent).use {
            val useTemplate = context.getString(R.string.template_chooser_use_template)
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodesWithText(useTemplate).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText(useTemplate)[0]
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodesWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT).assertTextContains(shared)
        }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val PREFS_NAME = "handtype_app_settings"
        private const val KEY_ONBOARDING = "onboarding_complete"
        private var previousOnboarding = false

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val prefs = ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            previousOnboarding = prefs.getBoolean(KEY_ONBOARDING, false)
            prefs.edit().putBoolean(KEY_ONBOARDING, true).commit()
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDING, previousOnboarding)
                .apply()
        }
    }
}
