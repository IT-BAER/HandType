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
 * A descender from the row above crosses into the top of a cell. The fixtures are scanned marker
 * cells: a g loop above an o, a y tail above a 6.
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
