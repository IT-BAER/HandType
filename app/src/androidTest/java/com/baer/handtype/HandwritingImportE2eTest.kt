package com.baer.handtype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.feature.capture.SheetSampleProcessor
import com.baer.handtype.feature.settings.AppSettingsStore
import com.baer.handtype.feature.settings.localizedFor
import com.baer.handtype.template.BundledTemplateRepository
import com.baer.handtype.testing.HandTypeDebugHooks
import com.baer.handtype.testing.HandTypeTestTags
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class HandwritingImportE2eTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun importsLastCameraImageRenamesTemplateAndGeneratesNote() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val context = baseContext.localizedFor(AppSettingsStore(baseContext).read().languagePreference)
        val repo = BundledTemplateRepository(context).userRepository()

        swipeTemplatePagerUntilVisible(LEGACY_TEMPLATE_NAME)
        composeRule.onNodeWithTag(HandTypeTestTags.templateDeleteButton(legacyTemplateId))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            repo.listUserTemplates().none { it.displayName == LEGACY_TEMPLATE_NAME }
        }

        composeRule.onNodeWithText(context.getString(R.string.template_chooser_premium_button))
            .performClick()
        composeRule.onNodeWithTag(HandTypeTestTags.CAMERA_SCAN_BUTTON).performClick()

        waitForText(context.getString(R.string.rename_handwriting_title))
        composeRule.onNodeWithTag(HandTypeTestTags.RENAME_HANDWRITING_INPUT)
            .performTextReplacement(NEW_TEMPLATE_NAME)
        composeRule.onNodeWithText(context.getString(R.string.rename_handwriting_confirm))
            .performClick()

        composeRule.onNodeWithText(NEW_TEMPLATE_NAME).assertIsDisplayed().performTouchInput {
            click()
        }
        composeRule.onNodeWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT)
            .assertIsDisplayed()
            .performTextReplacement(EXPECTED_DEFAULT_NOTE_TEXT)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag(HandTypeTestTags.backgroundPresetCard("ruled_sheet"))
            .performScrollTo().performClick()
        composeRule.onNodeWithTag(HandTypeTestTags.RENDER_GENERATE_BUTTON).performScrollTo().performClick()

        waitForResultImage(context)
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        val imageBitmap = composeRule.onNodeWithTag(HandTypeTestTags.RENDER_RESULT_IMAGE)
            .captureToImage()
            .asAndroidBitmap()
        val analysis = analyzeBitmap(imageBitmap)
        assertTrue(
            "Result image too blank: changed=${analysis.changedPixels}, dark=${analysis.darkPixels}",
            analysis.changedPixels > 12_000 && analysis.darkPixels > 2_500,
        )

        val latestHistoryImage = latestHistoryImage(context)
        assertTrue("Missing archived history image", latestHistoryImage?.isFile == true)
        val historyBaseName = requireNotNull(latestHistoryImage).nameWithoutExtension
        val sourceText = File(latestHistoryImage.parentFile, "$historyBaseName.txt").readText()
        val displayName = File(latestHistoryImage.parentFile, "$historyBaseName.name").readText().trim()
        assertEquals(EXPECTED_DEFAULT_NOTE_TEXT, sourceText)
        assertEquals(NEW_TEMPLATE_NAME, displayName)
        assertTrue("Existing templates must survive E2E tests",
            repo.listUserTemplates().map { it.id }.containsAll(existingTemplateIds))
        assertTrue("Existing history must survive E2E tests",
            File(context.filesDir, "history").list().orEmpty().toSet().containsAll(existingHistoryFiles))

        Log.i(
            TAG,
            "Fixture=$LAST_CAMERA_IMAGE_FILE_NAME history=${latestHistoryImage.name} " +
                "changed=${analysis.changedPixels} dark=${analysis.darkPixels}",
        )
    }

    private fun swipeTemplatePagerUntilVisible(templateName: String) {
        repeat(6) {
            if (composeRule.onAllNodesWithText(templateName).fetchSemanticsNodes().isNotEmpty()) {
                return
            }
            composeRule.onNodeWithTag(HandTypeTestTags.TEMPLATE_PAGER).performTouchInput {
                swipeLeft()
            }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText(templateName).assertIsDisplayed()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForResultImage(context: Context) {
        val contentDescription = context.getString(R.string.render_result_image_desc)
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription(contentDescription).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun analyzeBitmap(bitmap: Bitmap): BitmapAnalysis {
        val background = bitmap.getPixel(0, 0)
        var changedPixels = 0
        var darkPixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (colorDistance(background, color) > 26) {
                    changedPixels++
                }
                if (luma(color) < 180) {
                    darkPixels++
                }
            }
        }
        return BitmapAnalysis(
            changedPixels = changedPixels,
            darkPixels = darkPixels,
        )
    }

    private fun latestHistoryImage(context: Context): File? {
        val historyDir = File(context.filesDir, "history")
        return historyDir.listFiles { file -> file.isFile && file.extension == "png" }
            ?.maxByOrNull { it.lastModified() }
    }

    companion object {
        private const val TAG = "HandwritingImportE2e"
        private const val TIMEOUT_MS = 25_000L
        private const val LAST_CAMERA_IMAGE_FILE_NAME = "last_camera_image.png"
        private val LEGACY_TEMPLATE_NAME = "E2E Legacy ${System.currentTimeMillis()}"
        private val NEW_TEMPLATE_NAME = "E2E Script ${System.currentTimeMillis()}"
        private const val EXPECTED_DEFAULT_NOTE_TEXT =
            "The quick brown fox jumps over the lazy dog. " +
                "Every morning she would sit by the window, watching " +
                "the rain trace patterns on the glass. It was a quiet " +
                "kind of beauty, the sort that only patience could reveal."

        private lateinit var legacyTemplateId: String
        private var existingTemplateIds = emptySet<String>()
        private var existingHistoryFiles = emptySet<String>()

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
            val targetContext = ApplicationProvider.getApplicationContext<Context>()
            val fixtureFile = File(targetContext.cacheDir, LAST_CAMERA_IMAGE_FILE_NAME)

            existingTemplateIds = BundledTemplateRepository(targetContext).listUserTemplates().map { it.id }.toSet()
            existingHistoryFiles = File(targetContext.filesDir, "history").list().orEmpty().toSet()
            copyAssetToFile(instrumentationContext, LAST_CAMERA_IMAGE_FILE_NAME, fixtureFile)
            HandTypeDebugHooks.importedScanPath = fixtureFile.absolutePath
            HandTypeDebugHooks.premiumUnlocked = true

            val bitmap = BitmapFactory.decodeFile(fixtureFile.absolutePath)
                ?: error("Could not decode scan fixture: ${fixtureFile.absolutePath}")
            val glyphs = SheetSampleProcessor.process(
                captured = bitmap,
                debugContext = targetContext,
                skipPageDetection = true,
            ).glyphs
            require(glyphs.size >= 40) {
                "Fixture '$LAST_CAMERA_IMAGE_FILE_NAME' produced too few glyphs: ${glyphs.size}"
            }

            val repository = BundledTemplateRepository(targetContext).userRepository()
            legacyTemplateId = runBlocking {
                repository.saveTemplate(
                    displayName = LEGACY_TEMPLATE_NAME,
                    glyphMap = glyphs,
                    isNew = true,
                )
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            HandTypeDebugHooks.clear()
            val targetContext = ApplicationProvider.getApplicationContext<Context>()
            val repository = BundledTemplateRepository(targetContext).userRepository()
            repository.listUserTemplates().map { it.id }.filterNot { it in existingTemplateIds }
                .forEach { repository.deleteTemplate(it) }
            File(targetContext.filesDir, "history").listFiles().orEmpty()
                .filterNot { it.name in existingHistoryFiles }.forEach { it.delete() }
        }

        private fun copyAssetToFile(context: Context, assetName: String, targetFile: File) {
            targetFile.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        private fun luma(color: Int): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }

        private fun colorDistance(a: Int, b: Int): Int {
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            return kotlin.math.abs(ar - br) + kotlin.math.abs(ag - bg) + kotlin.math.abs(ab - bb)
        }
    }
}

private data class BitmapAnalysis(
    val changedPixels: Int,
    val darkPixels: Int,
)
