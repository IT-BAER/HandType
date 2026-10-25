package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.feature.capture.PracticeSheetGenerator
import com.baer.handtype.ml.GlyphPostProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A descender crosses a cell border. The fixtures are scanned cells: a g loop above an o and a
 * y tail above a 6 (marker), and a j whose loop runs into the cell below (tremor hand).
 */
@RunWith(AndroidJUnit4::class)
class GlyphCellSpillTest {

    @Test
    fun descenderFromTheCellAboveIsNotPartOfTheLetter() = assertNoSpill("marker_o_with_descender_above_cell.png")

    @Test
    fun descenderAboveATallLetterIsNotPartOfIt() = assertNoSpill("marker_6_with_descender_above_cell.png")

    @Test
    fun aTopStrokeThatOnlyTouchesTheCellEdgeIsKept() {
        val crop = crop("cursive_f_capital_bar_at_top_edge_cell.png")
        // The F's top stroke ends at the edge: nothing crosses in from the cell above.
        val kept = ink(GlyphPostProcessor.cleanGlyph(crop, guides(crop), inkAboveCrop = BooleanArray(crop.width)))
        val asSpill = ink(GlyphPostProcessor.cleanGlyph(crop, guides(crop), inkAboveCrop = BooleanArray(crop.width) { true }))
        assertTrue("F ink $kept, without its top stroke $asSpill", kept > asSpill * 1.2f)
    }

    @Test
    fun aDescenderFollowedIntoTheCellBelowKeepsItsLength() {
        // The scanner extended this 145-row j crop by the loop it drew into the cell below.
        val crop = crop("tremor_j_descender_below_cell.png")
        val cell = 145 / 0.94f
        val upper = (cell * (PracticeSheetGenerator.GUIDE_UPPER_RATIO - 0.03f)).toInt()
        val baseline = (cell * (PracticeSheetGenerator.GUIDE_BASELINE_RATIO - 0.03f)).toInt()
        val glyph = GlyphPostProcessor.cleanGlyph(crop, intArrayOf(upper, baseline), expectsDiacritic = true)

        val sourceBottom = lowestRow(crop) { p ->
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 < 120
        }
        val glyphBottom = lowestRow(glyph) { p -> (p ushr 24) > 40 }
        val sourceDepth = (sourceBottom - baseline).toFloat() / (baseline - upper)
        val glyphDepth = (glyphBottom - GlyphPostProcessor.normalizedBaselineRow()).toFloat() /
            GlyphPostProcessor.normalizedGuideBandHeight()
        assertTrue("descender depth $glyphDepth, written $sourceDepth", glyphDepth >= sourceDepth * 0.9f)
        assertTrue("descender reaches the canvas edge", glyphBottom < glyph.height - 2)
    }

    private fun lowestRow(bitmap: Bitmap, isInk: (Int) -> Boolean): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.indices.filter { isInk(pixels[it]) }.maxOf { it / bitmap.width }
    }

    private fun crop(fixture: String): Bitmap = InstrumentationRegistry.getInstrumentation().context.assets
        .open(fixture).use { BitmapFactory.decodeStream(it) }

    // Guide rows in a scanner crop, which drops 3 % of the cell at the top.
    private fun guides(crop: Bitmap): IntArray {
        val cell = crop.height / 0.94f
        return floatArrayOf(PracticeSheetGenerator.GUIDE_UPPER_RATIO, PracticeSheetGenerator.GUIDE_BASELINE_RATIO)
            .map { (cell * (it - 0.03f)).toInt() }.toIntArray()
    }

    private fun ink(glyph: Bitmap): Long = IntArray(glyph.width * glyph.height)
        .also { glyph.getPixels(it, 0, glyph.width, 0, 0, glyph.width, glyph.height) }
        .sumOf { (it ushr 24).toLong() }

    private fun assertNoSpill(fixture: String) {
        val crop = crop(fixture)
        val guides = guides(crop)
        // The scanner saw the descender above the cell border across the whole width.
        val glyph = GlyphPostProcessor.cleanGlyph(crop, guides, inkAboveCrop = BooleanArray(crop.width) { true })
        // Ink well above the cap line of the normalized canvas came from another cell.
        val band = GlyphPostProcessor.normalizedGuideBandHeight()
        val capHeight = band * (PracticeSheetGenerator.GUIDE_BASELINE_RATIO - PracticeSheetGenerator.GUIDE_CAP_RATIO) /
            (PracticeSheetGenerator.GUIDE_BASELINE_RATIO - PracticeSheetGenerator.GUIDE_UPPER_RATIO)
        val capRow = (GlyphPostProcessor.normalizedBaselineRow() - capHeight - band * 0.15f).toInt()
        val pixels = IntArray(glyph.width * capRow.coerceAtLeast(1))
        glyph.getPixels(pixels, 0, glyph.width, 0, 0, glyph.width, capRow.coerceAtLeast(1))
        val inkAbove = pixels.count { (it ushr 24) > 20 }
        assertTrue("$fixture: $inkAbove ink pixels above the cap line", inkAbove == 0)
    }
}
