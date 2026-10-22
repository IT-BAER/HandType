package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.feature.capture.PracticeSheetGenerator
import com.baer.handtype.feature.capture.SheetSampleProcessor
import com.baer.handtype.template.BundledTemplateRepository
import com.baer.handtype.template.HandwritingBitmapRenderer
import com.baer.handtype.template.HandwritingRenderConfig
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The German letters through every path: printed sheet, scan extraction and the bundled script. */
@RunWith(AndroidJUnit4::class)
class LocaleCharactersTest {

    private val german = PracticeSheetGenerator.localeCharacters(Locale.GERMANY)

    @Test
    fun writtenGermanSheetYieldsEveryLetterWithItsDots() {
        val cells = PracticeSheetGenerator.cellCharacters(german)
        val glyphs = SheetSampleProcessor.process(writtenSheet(cells), skipPageDetection = true, localeCharacters = german).glyphs
        if (InstrumentationRegistry.getArguments().getString("dump") == "true") dump(glyphs)

        assertEquals(cells.filter { it != ' ' }.toSet(), glyphs.keys)
        for (c in "ÄÖÜäöü") {
            val glyph = glyphs.getValue(c)
            assertEquals("'$c' body + two dots", 3, inkComponents(glyph))
            assertTrue("'$c' dots clipped at the canvas top", (0 until glyph.width).all { glyph.getPixel(it, 0) ushr 24 < ALPHA_MIN })
        }
    }

    @Test
    fun legacySheetStillMapsItsEightRows() {
        val cells = PracticeSheetGenerator.cellCharacters(rows = PracticeSheetGenerator.LEGACY_GRID_ROWS)
        val glyphs = SheetSampleProcessor.process(writtenSheet(cells), skipPageDetection = true, localeCharacters = german).glyphs

        assertEquals(cells.filter { it != ' ' }.toSet(), glyphs.keys)
    }

    @Test
    fun bundledScriptCarriesTheGermanLetters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val template = runBlocking { BundledTemplateRepository(context).loadTemplate("classic_script") }

        german.forEach { assertEquals("$it", 8, template.glyphs[it]?.size) }
        val result = HandwritingBitmapRenderer.render(
            "Grüße aus Österreich. Äpfel, Öl und Übermut",
            template,
            HandwritingRenderConfig(maxWidthPx = 900),
            seed = 42,
        )
        if (InstrumentationRegistry.getArguments().getString("dump") == "true") dump(mapOf('R' to result.bitmap))
        assertEquals(emptySet<Char>(), result.missingCharacters)
    }

    /** The generated sheet with every cell written in a plain font resting on the baseline guide. */
    private fun writtenSheet(cells: List<Char>): Bitmap {
        val sheet = PracticeSheetGenerator.renderBitmap(cells).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(sheet)
        val layout = PracticeSheetGenerator.fiducialLayout
        val gridLeft = layout.topLeft.first
        val gridTop = layout.topLeft.second
        val gridWidth = layout.topRight.first - gridLeft
        val gridHeight = layout.bottomLeft.second - gridTop
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 2f
            typeface = Typeface.SANS_SERIF
            textAlign = Paint.Align.CENTER
        }
        PracticeSheetGenerator.normalizedCells(cells).filter { it.character != ' ' }.forEach { cell ->
            val top = gridTop + cell.top * gridHeight
            val height = (cell.bottom - cell.top) * gridHeight
            val guideGap = height * (PracticeSheetGenerator.GUIDE_BASELINE_RATIO - PracticeSheetGenerator.GUIDE_UPPER_RATIO)
            // Sans-serif capitals stand about 0.72 of the text size, so they fill the guide gap.
            paint.textSize = guideGap / 0.72f
            canvas.drawText(
                cell.character.toString(),
                gridLeft + (cell.left + cell.right) / 2f * gridWidth,
                top + height * PracticeSheetGenerator.GUIDE_BASELINE_RATIO,
                paint,
            )
        }
        return sheet
    }

    /** Writes every glyph as u%04X.png under the app's external files dir for visual diffing. */
    private fun dump(glyphs: Map<Char, Bitmap>) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!.resolve("handtype_test/locale").apply { mkdirs() }
        glyphs.forEach { (char, bitmap) ->
            dir.resolve("u%04X.png".format(char.code)).outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Count of 8-connected components of opaque pixels. */
    private fun inkComponents(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val ink = BooleanArray(w * h) { pixels[it] ushr 24 >= ALPHA_MIN }
        var count = 0
        val stack = ArrayDeque<Int>()
        for (start in ink.indices) {
            if (!ink[start]) continue
            count++
            ink[start] = false
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                for (dy in -1..1) for (dx in -1..1) {
                    val x = i % w + dx
                    val y = i / w + dy
                    if (x < 0 || y < 0 || x >= w || y >= h || !ink[y * w + x]) continue
                    ink[y * w + x] = false
                    stack.addLast(y * w + x)
                }
            }
        }
        return count
    }

    private companion object {
        const val ALPHA_MIN = 40
    }
}
