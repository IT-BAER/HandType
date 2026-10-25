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

    private fun inkPieces(seed: Long): Int {
        val glyph = u()
        val template = HandwritingTemplate(
            descriptor = TemplateDescriptor("user_u1", "Test", "", "", premium = false),
            glyphs = "acdeimnorsuvwxz".associateWith { listOf(glyph) },
        )
        val note = HandwritingBitmapRenderer.render(
            "unu",
            template,
            HandwritingRenderConfig(maxWidthPx = 1000, targetLineHeightPx = 214, noteBackground = NoteBackgroundCatalog.transparentPreset),
            seed = seed,
        ).bitmap
        val w = note.width
        val ink = IntArray(w * note.height).also { note.getPixels(it, 0, w, 0, 0, w, note.height) }.map { (it ushr 24) > 128 }
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
        for (seed in 1L..5L) assertEquals("seed $seed", 1, inkPieces(seed))
    }
}
