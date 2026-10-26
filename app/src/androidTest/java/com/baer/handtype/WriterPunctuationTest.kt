package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.ml.GlyphPostProcessor
import com.baer.handtype.template.AccentComposer
import com.baer.handtype.template.PunctuationSynthesizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

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

    // An l leaning 22 px over its 110 rows, drawn with a 6 px pen.
    private val leaning = glyphs + ('l' to listOf(glyph(50) { c, p ->
        c.drawPath(Path().apply {
            moveTo(34f, baseline - 110f); lineTo(40f, baseline - 110f)
            lineTo(18f, baseline.toFloat()); lineTo(12f, baseline.toFloat()); close()
        }, p)
    }))

    // A cursive l leaning 0.2 px per row with a loop over its top 40 rows, written with a soft-edged pen:
    // full ink 1.5 px either side of its middle line, fading out over the next 2.5 px.
    private val leanPerRow = 0.2f
    private val cursive = mapOf('l' to listOf(softGlyph(70) { add ->
        fun centre(y: Float) = 20f + leanPerRow * (baseline - y)
        var y = baseline.toFloat()
        while (y >= baseline - 110f) {
            if (y < baseline - 70f) { add(centre(y) - 7f, y); add(centre(y) + 7f, y) } else add(centre(y), y)
            y -= 0.5f
        }
        var x = centre(baseline - 110f) - 7f
        while (x <= centre(baseline - 110f) + 7f) { add(x, baseline - 110f); x += 0.5f }
    }))

    @Test
    fun theDotOfAnExclamationMarkSitsOnItsLeaningStem() {
        val mark = PunctuationSynthesizer.synthesize('!', marks = PunctuationSynthesizer.WriterMarks.from(leaning))!!
        val (stem, dot) = stemAndDot(mark)
        val (a, b) = fit(rowCentres(mark, stem))
        val dotY = dot.average().toFloat()
        val dotX = rowCentres(mark, dot).let { c -> c.sumOf { it.second.toDouble() } / c.size }.toFloat()
        assertEquals("dot x on the stem line at row $dotY", a + b * dotY, dotX, 2f)
    }

    @Test
    fun aCommaIsNoDarkerThanTheDotAndPenItIsMadeOf() {
        val faintDot = mapOf('i' to listOf(glyph(40) { c, p ->
            c.drawRect(17f, baseline - 60f, 23f, baseline.toFloat(), p)
            p.alpha = 160
            c.drawRect(14f, baseline - 90f, 26f, baseline - 80f, p)
        }))
        val ink = PunctuationSynthesizer.InkStyle.DEFAULT
        val comma = PunctuationSynthesizer.synthesize(',', ink, PunctuationSynthesizer.WriterMarks.from(faintDot))!!
        val darkest = pixels(comma).maxOf { it ushr 24 }
        assertTrue("comma alpha $darkest, dot 160, pen ${ink.argb ushr 24}", darkest <= maxOf(160, ink.argb ushr 24) + 1)
    }

    @Test
    fun anExclamationMarkWithoutAWriterStemLeansAndFadesLikeTheHand() {
        val marks = PunctuationSynthesizer.WriterMarks.from(cursive)
        val mark = PunctuationSynthesizer.synthesize('!', marks = marks)!!
        val (stem, _) = stemAndDot(mark)
        val lean = -fit(rowCentres(mark, stem)).second
        assertEquals("lean per row", leanPerRow, lean, 0.07f)
        val l = cursive.getValue('l')[0]
        val writerSoft = softPixels(rowOf(l, baseline - 30))
        val markSoft = softPixels(rowOf(mark, stem[stem.size / 2]))
        assertTrue("soft edge pixels: mark $markSoft, writer $writerSoft", markSoft >= writerSoft - 1)
    }

    @Test
    fun aHyphenWithoutAWriterStemFadesLikeTheHand() {
        val l = cursive.getValue('l')[0]
        val writerSoft = softPixels(rowOf(l, baseline - 30))
        val hyphen = PunctuationSynthesizer.synthesize('-', marks = PunctuationSynthesizer.WriterMarks.from(cursive))!!
        val column = IntArray(hyphen.height).also { hyphen.getPixels(it, 0, 1, hyphen.width / 2, 0, 1, hyphen.height) }
        val markSoft = softPixels(column)
        assertTrue("soft edge pixels: hyphen $markSoft, writer $writerSoft", markSoft >= writerSoft - 1)
    }

    // An x-height letter without marks of its own: a 12 px block 60 rows tall on the baseline.
    private val block = glyph(40) { c, p -> c.drawRect(14f, baseline - 60f, 26f, baseline.toFloat(), p) }

    @Test
    fun anAccentFadesLikeTheHand() {
        val composed = AccentComposer.compose(
            block, baseline, AccentComposer.decompose('é')!!, unitPx = 60f, PunctuationSynthesizer.InkStyle.DEFAULT,
            PunctuationSynthesizer.WriterMarks.from(cursive),
        )
        val (mark, _) = stemAndDot(composed.bitmap)
        val writerSoft = softPixels(rowOf(cursive.getValue('l')[0], baseline - 30))
        val markSoft = softPixels(rowOf(composed.bitmap, mark[mark.size / 2]))
        assertTrue("soft edge pixels: acute $markSoft, writer $writerSoft", markSoft >= writerSoft - 1)
    }

    @Test
    fun theDotsOfAnUmlautAreTheWritersIDot() {
        val faintDot = mapOf('i' to listOf(glyph(40) { c, p ->
            c.drawRect(17f, baseline - 60f, 23f, baseline.toFloat(), p)
            p.alpha = 160
            c.drawRect(14f, baseline - 90f, 26f, baseline - 80f, p)
        }))
        val composed = AccentComposer.compose(
            block, baseline, AccentComposer.decompose('ë')!!, unitPx = 60f, PunctuationSynthesizer.InkStyle.DEFAULT,
            PunctuationSynthesizer.WriterMarks.from(faintDot),
        )
        val (dots, _) = stemAndDot(composed.bitmap)
        val px = pixels(composed.bitmap)
        val darkest = dots.maxOf { y -> (0 until composed.bitmap.width).maxOf { px[y * composed.bitmap.width + it] ushr 24 } }
        assertTrue("dot alpha $darkest, the writer's dot 160", darkest in 150..161)
    }

    @Test
    fun aQuoteIsAShortPieceOfTheWritersStem() {
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

    // Alpha 230 within 1.5 px of the nearest stroke point, fading to 0 at 4 px.
    private fun softGlyph(width: Int, strokes: ((Float, Float) -> Unit) -> Unit): Bitmap {
        val points = mutableListOf<Pair<Float, Float>>().also { list -> strokes { x, y -> list += x to y } }
        val height = 250
        val px = IntArray(width * height) { i ->
            val x = i % width + 0.5f
            val y = i / width + 0.5f
            val d = points.minOf { (px, py) -> hypot(px - x, py - y) }
            val a = (230 * ((4f - d) / 2.5f).coerceIn(0f, 1f)).toInt()
            (a shl 24) or 0x1A1410
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, width, 0, 0, width, height) }
    }

    // Ink rows of a mark from the top: the stem rows, then after a clear gap the dot rows.
    private fun stemAndDot(b: Bitmap): Pair<List<Int>, List<Int>> {
        val px = pixels(b)
        val rows = (0 until b.height).filter { y -> (0 until b.width).any { (px[y * b.width + it] ushr 24) > 40 } }
        val gap = rows.zipWithNext().indexOfLast { (a, c) -> c - a > 1 }
        return rows.take(gap + 1) to rows.drop(gap + 1)
    }

    private fun rowOf(b: Bitmap, y: Int) = IntArray(b.width).also { b.getPixels(it, 0, b.width, 0, y, b.width, 1) }

    // Alpha-weighted ink centre of each row.
    private fun rowCentres(b: Bitmap, rows: List<Int>): List<Pair<Int, Float>> = rows.map { y ->
        val row = rowOf(b, y)
        val weight = row.sumOf { (it ushr 24).toDouble() }
        y to (row.indices.sumOf { x -> (x + 0.5) * (row[x] ushr 24) } / weight).toFloat()
    }

    // Least squares x = a + b y.
    private fun fit(points: List<Pair<Int, Float>>): Pair<Float, Float> {
        val my = points.map { it.first.toDouble() }.average()
        val mx = points.map { it.second.toDouble() }.average()
        val b = points.sumOf { (y, x) -> (y - my) * (x - mx) } / points.sumOf { (y, _) -> (y - my) * (y - my) }
        return (mx - b * my).toFloat() to b.toFloat()
    }

    // Pixels of a cut through one stroke between the paper and the stroke's own core.
    private fun softPixels(cut: IntArray): Int {
        val alpha = cut.map { it ushr 24 }
        val core = alpha.max()
        return alpha.count { it in 41 until core * 8 / 10 }
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun inkMass(b: Bitmap): Long = pixels(b).sumOf { (it ushr 24).toLong() }

    private fun lowestInkRow(b: Bitmap): Int = pixels(b).let { px -> px.indices.filter { (px[it] ushr 24) > 40 }.maxOf { it / b.width } }
}
