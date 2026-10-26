package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.feature.capture.PracticeSheetGenerator
import com.baer.handtype.ml.GlyphPostProcessor
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A dotted letter must keep the stroke weight of the same crop cleaned as a plain letter: only the
 * dot is drawn at full opacity. The fixtures are scanned cursive cells whose anti-aliased stroke
 * edges break into small faint fragments.
 */
@RunWith(AndroidJUnit4::class)
class GlyphStrokeWeightTest {

    @Test
    fun dottedLetterKeepsItsStrokeWeight() = assertPlainWeight("cursive_i_cell.png")

    @Test
    fun umlautKeepsItsStrokeWeight() = assertPlainWeight("cursive_a_umlaut_cell.png")

    @Test
    fun printedIDotKeepsTheWritersWeight() = assertMarkWeight("print_i_cell.png")

    @Test
    fun pencilUmlautDotsKeepTheWritersWeight() = assertMarkWeight("pencil_a_umlaut_cell.png")

    @Test
    fun dotOnTheCapGuideIsKept() = assertMarkWeight("cursive_i_cell.png")

    @Test
    fun umlautDotsOnTheCapGuideAreKept() = assertMarkWeight("cursive_a_umlaut_cell.png")

    @Test
    fun bothCapitalUmlautDotsAreKept() = assertMarkWeight("pencil_o_umlaut_capital_cell.png")

    @Test
    fun bothPrintedCapitalUmlautDotsAreKept() = assertMarkWeight("print_a_umlaut_capital_cell.png")

    @Test
    fun umlautDotsBetweenTheArmsAreKept() = assertMarkWeight("pencil_u_umlaut_capital_cell.png")

    @Test
    fun markerUmlautDotsAtDifferentHeightsAreKept() = assertMarkWeight("marker_a_umlaut_capital_cell.png")

    @Test
    fun tinyUmlautDotsBelowTheGuideAreKeptAndTheGuideIsNot() = assertMarkWeight("tiny_u_umlaut_cell.png")

    @Test
    fun dottedLetterEdgesAreSmooth() {
        for (fixture in listOf("cursive_i_cell.png", "print_a_umlaut_capital_cell.png")) {
            val alpha = pixels(clean(crop(fixture), expectsDiacritic = true)).map { it ushr 24 }
            // Unfiltered upscaling repeats each edge pixel; a filtered edge rarely repeats a partial alpha.
            var edges = 0
            var repeats = 0
            for (i in 0 until alpha.size - 1) {
                if (alpha[i] in 1..254) {
                    edges++
                    if (alpha[i + 1] == alpha[i]) repeats++
                }
            }
            assertTrue("$fixture: $repeats of $edges edge pixels repeat", repeats < edges * 0.1f)
        }
    }

    private fun crop(fixture: String): Bitmap = InstrumentationRegistry.getInstrumentation().context.assets
        .open(fixture).use { BitmapFactory.decodeStream(it) }

    /** Guide rows in a scanner crop, which drops 3 % of the cell at the top and bottom. */
    private fun guides(crop: Bitmap, vararg ratios: Float): IntArray {
        val cell = crop.height / 0.94f
        return ratios.map { (cell * (it - 0.03f)).toInt() }.toIntArray()
    }

    private fun clean(crop: Bitmap, expectsDiacritic: Boolean): Bitmap = GlyphPostProcessor.cleanGlyph(
        crop,
        guides(crop, PracticeSheetGenerator.GUIDE_UPPER_RATIO, PracticeSheetGenerator.GUIDE_BASELINE_RATIO),
        expectsDiacritic = expectsDiacritic,
        outerGuideYsInCrop = guides(crop, PracticeSheetGenerator.GUIDE_CAP_RATIO, PracticeSheetGenerator.GUIDE_DESCENDER_RATIO),
    )

    private fun assertPlainWeight(fixture: String) {
        val crop = crop(fixture)
        val dotted = bodyInk(clean(crop, expectsDiacritic = true))
        val plain = bodyInk(clean(crop, expectsDiacritic = false))
        assertTrue("$fixture: dotted body ink $dotted, plain $plain", dotted <= plain * 1.15f)
    }

    /** The marks keep the writer's share of the letter's ink: a dot is not heavier than the pen drew it. */
    private fun assertMarkWeight(fixture: String) {
        val crop = crop(fixture)
        val glyph = clean(crop, expectsDiacritic = true)
        val written = markShare(pixels(crop).map { p ->
            val luma = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            if (luma < 200) (235 - luma).toFloat() else 0f
        }.toFloatArray(), crop.width)
        val cleaned = markShare(pixels(glyph).map { p ->
            (p ushr 24).let { if (it > 20) it.toFloat() else 0f }
        }.toFloatArray(), glyph.width)
        assertTrue("$fixture: ${cleaned.count} marks, written ${written.count}", cleaned.count == written.count)
        assertTrue("$fixture: mark share ${cleaned.share}, written ${written.share}", cleaned.share in written.share / 1.5f..written.share * 1.5f)
        assertTrue("$fixture: mark height ${cleaned.height}, written ${written.height}", abs(cleaned.height - written.height) <= 0.15f)
    }

    /** Marks' ink over the body's, their count, and how far their top sits above the body in body heights. */
    private class Marks(val share: Float, val count: Int, val height: Float)

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height)
        .also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    /** The body is the lowest large piece; marks are the pieces above it. */
    private fun markShare(weights: FloatArray, width: Int): Marks {
        class Piece(var mass: Float = 0f, var pixels: Int = 0, var top: Int = Int.MAX_VALUE, var bottom: Int = -1)
        val height = weights.size / width
        val seen = BooleanArray(weights.size)
        val pieces = mutableListOf<Piece>()
        val queue = ArrayDeque<Int>()
        for (start in weights.indices) {
            if (weights[start] <= 0f || seen[start]) continue
            val piece = Piece().also { pieces += it }
            seen[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val x = i % width
                val y = i / width
                piece.mass += weights[i]
                piece.pixels++
                piece.top = minOf(piece.top, y)
                piece.bottom = maxOf(piece.bottom, y)
                for (ny in y - 1..y + 1) for (nx in x - 1..x + 1) {
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val j = ny * width + nx
                    if (weights[j] > 0f && !seen[j]) { seen[j] = true; queue.add(j) }
                }
            }
        }
        val largest = pieces.maxOf { it.mass }
        val body = pieces.filter { it.mass >= largest / 2 }.maxBy { it.bottom }
        val marks = pieces.filter { it.bottom < body.top && it.pixels > 3 }
        return Marks(
            share = marks.sumOf { it.mass.toDouble() }.toFloat() / body.mass,
            count = marks.size,
            height = (body.top - (marks.minOfOrNull { it.top } ?: body.top)).toFloat() / (body.bottom - body.top + 1),
        )
    }

    /** Alpha sum below the dots: ink mass, not opaque pixel count. */
    private fun bodyInk(glyph: Bitmap): Long {
        val bodyTop = GlyphPostProcessor.canvasBaselineRow(glyph.height) -
            (GlyphPostProcessor.normalizedGuideBandHeight() * 0.7f).toInt()
        val pixels = IntArray(glyph.width * (glyph.height - bodyTop))
        glyph.getPixels(pixels, 0, glyph.width, 0, bodyTop, glyph.width, glyph.height - bodyTop)
        return pixels.sumOf { (it ushr 24).toLong() }
    }
}
