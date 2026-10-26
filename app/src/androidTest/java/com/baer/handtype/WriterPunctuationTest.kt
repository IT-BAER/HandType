package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.ml.GlyphPostProcessor
import com.baer.handtype.template.PunctuationSynthesizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Punctuation a template lacks is drawn with the writer's own i dot and stem. */
@RunWith(AndroidJUnit4::class)
class WriterPunctuationTest {

    private val baseline = GlyphPostProcessor.normalizedBaselineRow()

    // An i with a 14x10 diamond-ish dot of mixed alpha, and a 6 px wide print l.
    private val glyphs: Map<Char, List<Bitmap>> = mapOf(
        'i' to listOf(glyph(40) { c, p ->
            c.drawRect(17f, baseline - 60f, 23f, baseline.toFloat(), p)
            p.alpha = 150
            c.drawRect(13f, baseline - 90f, 27f, baseline - 80f, p)
            p.alpha = 255
            c.drawRect(16f, baseline - 88f, 24f, baseline - 82f, p)
        }),
        'l' to listOf(glyph(30) { c, p -> c.drawRect(12f, baseline - 110f, 18f, baseline.toFloat(), p) }),
    )

    @Test
    fun aFullStopIsTheWritersIDot() {
        val marks = PunctuationSynthesizer.WriterMarks.from(glyphs)
        val stop = PunctuationSynthesizer.synthesize('.', marks = marks)!!
        val dot = glyphs.getValue('i')[0].let { Bitmap.createBitmap(it, 13, baseline - 90, 14, 10) }
        assertEquals("ink of the full stop", inkMass(dot), inkMass(stop))
        assertEquals("rests on the baseline", baseline - 1, lowestInkRow(stop))
    }

    @Test
    fun anExclamationMarkIsTheWritersStemOverTheDot() {
        val marks = PunctuationSynthesizer.WriterMarks.from(glyphs)
        val mark = PunctuationSynthesizer.synthesize('!', marks = marks)!!
        // The stem keeps the l's 6 px width at mid height.
        val row = IntArray(mark.width).also { mark.getPixels(it, 0, mark.width, 0, baseline - 50, mark.width, 1) }
        val stemWidth = row.count { (it ushr 24) > 128 }
        assertTrue("stem width $stemWidth", stemWidth in 5..7)
        assertEquals("dot on the baseline", baseline - 1, lowestInkRow(mark))
    }

    @Test
    fun anExclamationMarkIsAsTallAsTheWritersTallLetters() {
        // A tiny hand: x-height letters 40 rows tall, l and digits 60.
        val tiny = PunctuationSynthesizer.InkStyle.DEFAULT.copy(xHeightTop = baseline - 40, ascenderTop = baseline - 60)
        for (c in "!?") {
            val mark = PunctuationSynthesizer.synthesize(c, tiny)!!
            val top = pixels(mark).let { px -> px.indices.first { (px[it] ushr 24) > 40 } / mark.width }
            assertTrue("'$c' top $top, tall letters from ${baseline - 60}", top in baseline - 60 - 4..baseline - 40)
        }
    }

    @Test
    fun aQuoteIsAShortPieceOfTheWritersStem() {
        // An l leaning 22 px over its 110 rows, drawn with a 6 px pen.
        val leaning = glyphs + ('l' to listOf(glyph(50) { c, p ->
            c.drawPath(Path().apply {
                moveTo(34f, baseline - 110f); lineTo(40f, baseline - 110f)
                lineTo(18f, baseline.toFloat()); lineTo(12f, baseline.toFloat()); close()
            }, p)
        }))
        val quote = PunctuationSynthesizer.synthesize('\'', marks = PunctuationSynthesizer.WriterMarks.from(leaning))!!
        // 18 rows of that lean add about 4 px to the pen; the whole 28 px wide stem squashed would not.
        val px = pixels(quote)
        val columns = px.indices.filter { (px[it] ushr 24) > 40 }.map { it % quote.width }
        val inkWidth = columns.max() - columns.min() + 1
        assertTrue("quote ink width $inkWidth", inkWidth <= 13)
    }

    private fun glyph(width: Int, draw: (Canvas, Paint) -> Unit): Bitmap =
        Bitmap.createBitmap(width, 250, Bitmap.Config.ARGB_8888).also {
            draw(Canvas(it), Paint().apply { color = 0xFF1A1410.toInt() })
        }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun inkMass(b: Bitmap): Long = pixels(b).sumOf { (it ushr 24).toLong() }

    private fun lowestInkRow(b: Bitmap): Int = pixels(b).let { px -> px.indices.filter { (px[it] ushr 24) > 40 }.maxOf { it / b.width } }
}
