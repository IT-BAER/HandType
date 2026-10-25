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

    private fun assertPlainWeight(fixture: String) {
        val crop = InstrumentationRegistry.getInstrumentation().context.assets
            .open(fixture).use { BitmapFactory.decodeStream(it) }
        // The scanner sets the diacritic zone from the x-height guide down to the baseline.
        val guides = floatArrayOf(PracticeSheetGenerator.GUIDE_UPPER_RATIO, PracticeSheetGenerator.GUIDE_BASELINE_RATIO)
            .map { (crop.height * it).toInt() }.toIntArray()
        val dotted = bodyInk(GlyphPostProcessor.cleanGlyph(crop, guides, expectsDiacritic = true))
        val plain = bodyInk(GlyphPostProcessor.cleanGlyph(crop, guides, expectsDiacritic = false))
        assertTrue("$fixture: dotted body ink $dotted, plain $plain", dotted <= plain * 1.15f)
    }

    /**
     * Alpha sum below the dots. Ink mass, not opaque pixel count: a glyph with a dot is scaled
     * without bitmap filtering, which sharpens the edges but keeps the mass.
     */
    private fun bodyInk(glyph: Bitmap): Long {
        val bodyTop = GlyphPostProcessor.normalizedBaselineRow() -
            (GlyphPostProcessor.normalizedGuideBandHeight() * 0.7f).toInt()
        val pixels = IntArray(glyph.width * (glyph.height - bodyTop))
        glyph.getPixels(pixels, 0, glyph.width, 0, bodyTop, glyph.width, glyph.height - bodyTop)
        return pixels.sumOf { (it ushr 24).toLong() }
    }
}
