package com.baer.handtype

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.capture.stylus.CellGeometry
import com.baer.handtype.feature.capture.stylus.InkPoint
import com.baer.handtype.feature.capture.stylus.InkRasterizer
import com.baer.handtype.feature.capture.stylus.InkStroke
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusGlyphBuildTest {
    private val aspect = CellGeometry.printed().aspectRatio

    // Polyline in cell heights (x from the letter's left edge, centred in the cell) and cell-fraction y.
    private fun stroke(width: Float, vararg xy: Float, samples: Int = 1): InkStroke {
        val corners = xy.toList().chunked(2).map { (x, y) -> InkPoint(0.5f + (x - width / 2f) / aspect, y, 0L) }
        val points = buildList {
            add(corners.first())
            for (i in 1 until corners.size) {
                val a = corners[i - 1]
                val b = corners[i]
                for (s in 1..samples) {
                    val t = s.toFloat() / samples
                    add(InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, 0L))
                }
            }
        }
        return InkStroke(points)
    }

    private fun inkPixels(c: Char, strokes: List<InkStroke>): Int {
        val bitmap = InkRasterizer.buildGlyphs(listOf(c), listOf(strokes)).getValue(c)
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 32) count++
        }
        return count
    }

    // A full-height I or l and a cell-wide W have the shape of the printed rules the scan path removes.
    @Test
    fun straightAndWideLettersKeepTheirInk() {
        val cases = listOf(
            'H' to listOf(stroke(0.4f, 0f, 0.12f, 0f, 0.78f), stroke(0.4f, 0.4f, 0.12f, 0.4f, 0.78f), stroke(0.4f, 0f, 0.45f, 0.4f, 0.45f)),
            'V' to listOf(stroke(0.4f, 0f, 0.12f, 0.2f, 0.78f, 0.4f, 0.12f)),
            'I' to listOf(stroke(0.2f, 0.1f, 0.12f, 0.1f, 0.78f)),
            'I' to listOf(stroke(0.2f, 0.1f, 0.12f, 0.1f, 0.78f, samples = 40)),
            'l' to listOf(stroke(0.1f, 0.05f, 0.12f, 0.05f, 0.78f)),
            'W' to listOf(stroke(0.5f, 0f, 0.12f, 0.12f, 0.78f, 0.25f, 0.4f, 0.38f, 0.78f, 0.5f, 0.12f)),
        )
        val results = cases.map { (c, strokes) -> c to inkPixels(c, strokes) }
        android.util.Log.i("StylusGlyphBuildTest", "ink pixels: $results")
        assertTrue("blank glyphs: $results", results.all { it.second > 0 })
    }
}
