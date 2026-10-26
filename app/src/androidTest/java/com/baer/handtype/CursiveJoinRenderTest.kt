package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.ml.GlyphPostProcessor
import com.baer.handtype.template.HandwritingBitmapRenderer
import com.baer.handtype.template.HandwritingRenderConfig
import com.baer.handtype.template.HandwritingTemplate
import com.baer.handtype.template.NoteBackgroundCatalog
import com.baer.handtype.template.TemplateDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The letters of a cursive hand render as one stroke per word. */
@RunWith(AndroidJUnit4::class)
class CursiveJoinRenderTest {

    private val baseline = GlyphPostProcessor.normalizedBaselineRow().toFloat()

    /** A cursive u on a normalized canvas: a lead-in from the baseline, the body, an exit tail. */
    private fun u(): Bitmap = Bitmap.createBitmap(84, 250, Bitmap.Config.ARGB_8888).also { b ->
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1410.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }
        val path = Path().apply {
            moveTo(10f, baseline - 3f)
            lineTo(30f, baseline - 40f)
            lineTo(30f, baseline - 10f)
            quadTo(30f, baseline - 3f, 42f, baseline - 3f)
            quadTo(54f, baseline - 3f, 54f, baseline - 10f)
            moveTo(54f, baseline - 60f)
            lineTo(54f, baseline - 3f)
            lineTo(74f, baseline - 25f)
        }
        Canvas(b).drawPath(path, pen)
    }

    /** A print x: two straight strokes crossing at mid x-height, no lead-in from the baseline. */
    private fun printX(): Bitmap = Bitmap.createBitmap(84, 250, Bitmap.Config.ARGB_8888).also { b ->
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1410.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }
        val path = Path().apply {
            moveTo(14f, baseline - 60f)
            lineTo(70f, baseline - 3f)
            moveTo(70f, baseline - 60f)
            lineTo(14f, baseline - 3f)
        }
        Canvas(b).drawPath(path, pen)
    }

    private fun render(text: String, glyphs: Map<Char, List<Bitmap>>, seed: Long): Bitmap = HandwritingBitmapRenderer.render(
        text,
        HandwritingTemplate(descriptor = TemplateDescriptor("user_u1", "Test", "", "", premium = false), glyphs = glyphs),
        HandwritingRenderConfig(maxWidthPx = 1000, targetLineHeightPx = 214, noteBackground = NoteBackgroundCatalog.transparentPreset),
        seed = seed,
    ).bitmap

    private fun ink(note: Bitmap): List<Boolean> =
        IntArray(note.width * note.height).also { note.getPixels(it, 0, note.width, 0, 0, note.width, note.height) }.map { (it ushr 24) > 128 }

    /** Ink pixels in the bottom quarter of the ink's bounding box: the feet of the letters. */
    private fun footInk(note: Bitmap): Int {
        val ink = ink(note)
        val rows = ink.indices.filter { ink[it] }.map { it / note.width }
        val top = rows.minOrNull() ?: return 0
        val bottom = rows.max()
        return rows.count { it >= bottom - (bottom - top) / 4 }
    }

    private fun inkPieces(note: Bitmap): Int {
        val w = note.width
        val ink = ink(note)
        val seen = BooleanArray(ink.size)
        var pieces = 0
        for (start in ink.indices) {
            if (!ink[start] || seen[start]) continue
            pieces++
            val queue = ArrayDeque(listOf(start)).also { seen[start] = true }
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                for (dy in -1..1) for (dx in -1..1) {
                    val x = i % w + dx
                    val j = i + dy * w + dx
                    if (x in 0 until w && j in ink.indices && ink[j] && !seen[j]) { seen[j] = true; queue.add(j) }
                }
            }
        }
        return pieces
    }

    @Test
    fun aCursiveWordIsOneStroke() {
        val glyphs = "acdeimnorsuvwxz".associateWith { listOf(u()) }
        for (seed in 1L..5L) assertEquals("seed $seed", 1, inkPieces(render("unu", glyphs, seed)))
    }

    @Test
    fun aPrintXInACursiveHandKeepsItsLowerLeftLeg() {
        val glyphs = "acdeimnorsuvwz".associateWith { listOf(u()) } + ('x' to listOf(printX()))
        for (seed in 1L..5L) {
            // No join after v, so "vx" draws the whole x next to the same u-shaped letter.
            val afterU = footInk(render("ux", glyphs, seed))
            val afterV = footInk(render("vx", glyphs, seed))
            assertTrue("seed $seed: foot ink after u $afterU, after v $afterV", afterU >= afterV * 0.95f)
        }
    }
}
