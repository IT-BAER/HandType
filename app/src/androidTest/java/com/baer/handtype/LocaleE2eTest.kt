package com.baer.handtype

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.feature.capture.PracticeSheetGenerator
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

/**
 * German and accented letters through the real app: editor, result, save, PDF export, history,
 * a captured handwriting template and the on-screen sheet written to the end. A screenshot of
 * every step lands in the app's external files dir under handtype_test/e2e for review.
 */
@RunWith(AndroidJUnit4::class)
class LocaleE2eTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val baseContext = ApplicationProvider.getApplicationContext<Context>()
    private val context = baseContext.localizedFor(AppSettingsStore(baseContext).read().languagePreference)

    @Test
    fun localeLettersThroughEditorResultExportHistoryCaptureAndSheet() {
        dismissOnboarding()
        shot("chooser")

        val inkScript = context.getString(R.string.template_ink_script_name)
        swipeTemplatePagerUntilVisible(inkScript)
        useTemplateOnScreen()
        writeNote()
        shot("ink_script_result")

        composeRule.onNodeWithText(context.getString(R.string.result_save_button)).performClick()
        waitForToastGap()
        shot("saved")
        composeRule.onNodeWithText(context.getString(R.string.result_export_sheet_title)).performClick()
        composeRule.waitForIdle()
        shot("export_sheet")
        composeRule.onNodeWithText(context.getString(R.string.result_export_pdf_button)).performClick()
        waitForToastGap()
        shot("pdf_exported")
        assertTrue("PNG and PDF land in the gallery and downloads", ownExports().size >= 2)

        Espresso.pressBack()
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_menu)).performClick()
        composeRule.onAllNodesWithText(context.getString(R.string.history_title))[0].performClick()
        composeRule.waitForIdle()
        Thread.sleep(800)
        shot("history")
        val latest = File(baseContext.filesDir, "history").listFiles { f -> f.extension == "txt" }!!.maxBy { it.lastModified() }
        assertEquals(NOTE, latest.readText())
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.history_note_desc))[0].performClick()
        composeRule.waitForIdle()
        shot("history_detail")
        Espresso.pressBack()
        Espresso.pressBack()
        composeRule.waitForIdle()

        swipeTemplatePagerUntilVisible(TEMPLATE_NAME)
        shot("chooser_captured_template")
        useTemplateOnScreen()
        // The real scan has no German row: ü and Ö are composed, ß has no base letter and stays missing.
        writeNote(expectedMissing = "ß")
        shot("captured_template_result")
        Espresso.pressBack()
        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.template_chooser_premium_button)).performScrollTo().performClick()
        composeRule.waitForIdle()
        shot("capture_intro")
        composeRule.onNodeWithText(context.getString(R.string.premium_instruction), substring = true).assertExists()
        composeRule.onNodeWithTag(HandTypeTestTags.CAMERA_WRITE_BUTTON).performClick()
        composeRule.waitForIdle()
        shot("stylus_first_cell")
        val cells = PracticeSheetGenerator.cellCharacters(composeRule.activity).count { it != ' ' }
        // The counter shows finished cells; the German row adds ÄÖÜäöüß to the 62 alphanumerics.
        composeRule.onNodeWithText(context.getString(R.string.stylus_progress, 0, cells)).assertIsDisplayed()
        if (composeRule.activity.resources.configuration.locales[0].language == "de") assertEquals(69, cells)
    }

    @Test
    fun stylusSheetWithLocaleLettersBuildsATemplateThatWritesTheNote() {
        dismissOnboarding()
        composeRule.onNodeWithText(context.getString(R.string.template_chooser_premium_button)).performScrollTo().performClick()
        composeRule.onNodeWithTag(HandTypeTestTags.CAMERA_WRITE_BUTTON).performClick()
        composeRule.waitForIdle()
        val cells = PracticeSheetGenerator.cellCharacters(composeRule.activity).filter { it != ' ' }
        val german = composeRule.activity.resources.configuration.locales[0].language == "de"
        if (german) assertTrue("German sheet has ÄÖÜäöüß", cells.containsAll("ÄÖÜäöüß".toList()))

        val overridden = mutableListOf<String>()
        var start = 0
        while (start < cells.size) {
            // The page shows the cells that exist on screen: 9, or 6 on a short screen.
            val page = (start until cells.size).takeWhile {
                composeRule.onAllNodesWithTag(HandTypeTestTags.stylusCell(it)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("page at $start shows cells", page.isNotEmpty())
            page.forEach { drawGlyph(it, cells[it]) }
            val pageText = page.map { cells[it] }.joinToString("")
            if (start == 0 || pageText.any { it.code > 0x7F } || page.last() == cells.lastIndex) shot("stylus_page_$start")
            val end = page.last() + 1
            val last = end == cells.size
            fun advanced() = if (last) {
                composeRule.onAllNodesWithTag(HandTypeTestTags.RENAME_HANDWRITING_INPUT).fetchSemanticsNodes().isNotEmpty()
            } else {
                stylusText(HandTypeTestTags.STYLUS_PROGRESS) == context.getString(R.string.stylus_progress, end, cells.size)
            }
            fun shapeWarning() = stylusWarning()?.takeIf { warning -> shapeHints().any { warning.contains(it) } }
            composeRule.onNodeWithTag(HandTypeTestTags.STYLUS_NEXT).performClick()
            composeRule.waitUntil(TIMEOUT_MS) { advanced() || stylusWarning() != null }
            if (!advanced()) {
                val warning = stylusWarning()!!
                // Shape hints mean a drawn letter is wrong; only recognizer mismatches may be kept.
                assertTrue("'$pageText': $warning", shapeWarning() == null)
                overridden += "$pageText: $warning"
                shot("stylus_override_$start")
                composeRule.onNodeWithTag(HandTypeTestTags.STYLUS_NEXT).performClick()
                composeRule.waitUntil(TIMEOUT_MS) { advanced() || shapeWarning() != null }
                assertTrue("'$pageText': ${stylusWarning()}", advanced())
            }
            start = end
        }
        android.util.Log.i("LocaleE2eTest", "${overridden.size} of ${cells.size} cells kept after a mismatch: $overridden")
        shot("stylus_rename")

        composeRule.onNodeWithTag(HandTypeTestTags.RENAME_HANDWRITING_INPUT).performTextReplacement(STYLUS_TEMPLATE_NAME)
        composeRule.onNodeWithText(context.getString(R.string.rename_handwriting_confirm)).performClick()
        composeRule.waitForIdle()
        val repository = BundledTemplateRepository(baseContext)
        val saved = repository.listUserTemplates().single { it.displayName == STYLUS_TEMPLATE_NAME }
        val glyphs = runBlocking { repository.loadTemplate(saved.id) }.glyphs.keys
        assertEquals("every written cell keeps a glyph", emptyList<Char>(), cells.filterNot { it in glyphs })

        swipeTemplatePagerUntilVisible(STYLUS_TEMPLATE_NAME)
        shot("stylus_chooser")
        useTemplateOnScreen()
        // Without the German row ß has no glyph and no base letter.
        writeNote(expectedMissing = if (german) null else "ß")
        shot("stylus_template_result")
    }

    // Null while the stylus screen is gone: after the last page it leaves before the rename dialog shows.
    private fun stylusText(tag: String): String? =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.Text)?.joinToString("")

    /** The hint line when it shows a warning, else null. */
    private fun stylusWarning(): String? = stylusText(HandTypeTestTags.STYLUS_HINT)?.takeUnless {
        it in listOf(R.string.stylus_instruction, R.string.stylus_note_shape_only, R.string.stylus_saving).map(context::getString)
    }

    private fun shapeHints() = listOf(
        R.string.stylus_hint_empty,
        R.string.stylus_hint_outside,
        R.string.stylus_hint_too_small,
        R.string.stylus_hint_too_large,
        R.string.stylus_hint_reach_up,
    ).map(context::getString)

    /** Writes [character] into sheet cell [index] as its font outline, one touch stroke per contour. */
    private fun drawGlyph(index: Int, character: Char) {
        composeRule.onNodeWithTag(HandTypeTestTags.stylusCell(index)).performTouchInput {
            for (stroke in outlineStrokes(character, width.toFloat(), height.toFloat())) {
                down(stroke.first())
                stroke.drop(1).forEach { moveTo(it) }
                up()
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * The glyph outline placed like the screen's ghost letter, at 1.6 guide gaps instead of 2 so
     * accented capitals stay inside the cell.
     */
    private fun outlineStrokes(character: Char, width: Float, height: Float): List<List<Offset>> {
        val gap = PracticeSheetGenerator.GUIDE_BASELINE_RATIO - PracticeSheetGenerator.GUIDE_UPPER_RATIO
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = height * gap * 1.6f }
        val text = character.toString()
        val path = Path()
        paint.getTextPath(text, 0, text.length, (width - paint.measureText(text)) / 2f, height * PracticeSheetGenerator.GUIDE_BASELINE_RATIO, path)
        val measure = PathMeasure(path, false)
        val step = height * 0.01f
        val position = FloatArray(2)
        return buildList {
            do {
                val length = measure.length
                if (length < step) continue
                add((0..(length / step).toInt()).map { i ->
                    measure.getPosTan(i * step, position, null)
                    Offset(position[0], position[1])
                })
            } while (measure.nextContour())
        }
    }

    /** [expectedMissing] is the only missing-glyph text allowed, or null for none. */
    private fun writeNote(expectedMissing: String? = null) {
        composeRule.onNodeWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT).performTextReplacement(NOTE)
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        shot("editor")
        composeRule.onNodeWithTag(HandTypeTestTags.RENDER_GENERATE_BUTTON).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(HandTypeTestTags.RENDER_RESULT_IMAGE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        val missingPrefix = context.getString(R.string.render_missing_glyphs, "").trim()
        val banner = composeRule.onAllNodesWithText(missingPrefix, substring = true).fetchSemanticsNodes()
        if (expectedMissing == null) {
            assertTrue("Missing glyphs shown", banner.isEmpty())
        } else {
            composeRule.onNodeWithText(context.getString(R.string.render_missing_glyphs, expectedMissing)).assertIsDisplayed()
        }
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
        buttons[buttons.fetchSemanticsNodes().indexOfFirst(::fullyVisible)].performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(HandTypeTestTags.RENDER_MESSAGE_INPUT).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The pager starts on any page and composes its neighbours, so swipe until the card is on screen. */
    private fun swipeTemplatePagerUntilVisible(name: String) {
        fun onScreen() = composeRule.onAllNodesWithText(name).fetchSemanticsNodes().any(::fullyVisible)
        // Edge swipes open the drawer or go back, so both directions start inside the card.
        for (swipe in listOf<androidx.compose.ui.test.TouchInjectionScope.() -> Unit>(
            { swipeRight(startX = width * 0.2f, endX = width * 0.9f) },
            { swipeLeft(startX = width * 0.8f, endX = width * 0.1f) },
        )) {
            repeat(6) {
                if (onScreen()) return
                composeRule.onNodeWithTag(HandTypeTestTags.TEMPLATE_PAGER).performTouchInput(swipe)
                composeRule.waitForIdle()
            }
        }
        composeRule.onNodeWithText(name).assertIsDisplayed()
    }

    /** boundsInRoot is clipped to the pager, so a peeking card is narrower than its own size. */
    private fun fullyVisible(node: androidx.compose.ui.semantics.SemanticsNode): Boolean =
        node.boundsInRoot.width >= node.size.width - 1f

    /** Lets the toast appear before the screenshot. */
    private fun waitForToastGap() {
        composeRule.waitForIdle()
        Thread.sleep(600)
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = baseContext.getExternalFilesDir(null)!!.resolve("handtype_test/e2e").apply { mkdirs() }
        dir.resolve("%02d_%s.png".format(frame++, name)).outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        private const val TIMEOUT_MS = 25_000L
        private const val NOTE = "Grüße aus Österreich! Café, señor, garçon, à bientôt.\nAção, naïve, Ångström, aquí."
        private val TEMPLATE_NAME = "E2E Akzente ${System.currentTimeMillis()}"
        private val STYLUS_TEMPLATE_NAME = "E2E Stift ${System.currentTimeMillis()}"
        private var frame = 0
        private var startedAtSeconds = 0L
        private var existingTemplateIds = emptySet<String>()
        private var existingHistoryFiles = emptySet<String>()

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val target = ApplicationProvider.getApplicationContext<Context>()
            startedAtSeconds = System.currentTimeMillis() / 1000
            target.getExternalFilesDir(null)!!.resolve("handtype_test/e2e").deleteRecursively()
            existingTemplateIds = BundledTemplateRepository(target).listUserTemplates().map { it.id }.toSet()
            existingHistoryFiles = File(target.filesDir, "history").list().orEmpty().toSet()
            HandTypeDebugHooks.premiumUnlocked = true

            val scan = InstrumentationRegistry.getInstrumentation().context.assets.open("last_camera_image.png")
                .use(BitmapFactory::decodeStream)
            val glyphs = SheetSampleProcessor.process(scan, skipPageDetection = true).glyphs
            runBlocking {
                BundledTemplateRepository(target).userRepository().saveTemplate(TEMPLATE_NAME, glyphs, isNew = true)
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            HandTypeDebugHooks.clear()
            val target = ApplicationProvider.getApplicationContext<Context>()
            val repository = BundledTemplateRepository(target).userRepository()
            repository.listUserTemplates().map { it.id }.filterNot { it in existingTemplateIds }
                .forEach { repository.deleteTemplate(it) }
            File(target.filesDir, "history").listFiles().orEmpty()
                .filterNot { it.name in existingHistoryFiles }.forEach { it.delete() }
            ownExports().forEach { target.contentResolver.delete(it, null, null) }
        }

        /** Gallery PNGs and downloaded PDFs this run wrote. */
        private fun ownExports(): List<Uri> {
            val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
            return listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ).flatMap { collection ->
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'HandType_%' AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                    arrayOf(startedAtSeconds.toString()),
                    null,
                )?.use { cursor ->
                    buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
                }.orEmpty()
            }
        }
    }
}
