package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.feature.capture.SheetSampleProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays a real photographed practice sheet through the extraction pipeline and asserts that no
 * glyph carries printed sheet furniture. The cell borders reach the component labeller as one
 * hollow rectangle around the whole crop; left in, it becomes the glyph and blows the canvas up to
 * several times the width of its neighbours.
 */
@RunWith(AndroidJUnit4::class)
class SheetExtractionQualityTest {

    @Test
    fun extractedGlyphsCarryNoPrintedFurniture() = assertCleanGlyphs(FIXTURE)

    /** The same sheet after the ML Kit document scanner cropped and enhanced it. */
    @Test
    fun scannerOutputGlyphsCarryNoPrintedFurniture() = assertCleanGlyphs(SCANNER_FIXTURE)

    private fun assertCleanGlyphs(fixture: String) {
        val captured = InstrumentationRegistry.getInstrumentation().context.assets
            .open(fixture).use { BitmapFactory.decodeStream(it) }
            ?: error("could not decode androidTest asset $fixture")

        val glyphs = SheetSampleProcessor.process(captured, skipPageDetection = true).glyphs
        if (InstrumentationRegistry.getArguments().getString("dump") == "true") dump(glyphs)
        assertTrue("no glyphs extracted from $fixture", glyphs.size >= EXPECTED_GLYPHS)

        val medianWidth = glyphs.values.map { it.width }.sorted()[glyphs.size / 2]
        val failures = mutableListOf<String>()
        glyphs.forEach { (char, bitmap) ->
            if (bitmap.width > medianWidth * MAX_WIDTH_FACTOR) {
                failures += "'$char' is ${bitmap.width}px wide, over " +
                    "${MAX_WIDTH_FACTOR}x the median $medianWidth"
            }
            val components = inkComponents(bitmap)
            // Dotted letters are body + dot in this hand; a third piece is the printed cell label.
            if (char in DOTTED && components.size != 2) {
                failures += "'$char' has ${components.size} ink components, expected body + dot"
            }
            components.forEach { (w, h) ->
                val aspect = w.toFloat() / h
                val spansCanvas = h > bitmap.height * SPAN_MIN || w > bitmap.width * SPAN_MIN
                if (spansCanvas && (aspect < MIN_ASPECT || aspect > 1f / MIN_ASPECT)) {
                    failures += "'$char' carries a ${w}x${h} printed rule"
                }
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    /** Writes every glyph as u%04X.png under the app's external files dir for visual diffing. */
    private fun dump(glyphs: Map<Char, Bitmap>) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!.resolve("handtype_test/out").apply { mkdirs() }
        glyphs.forEach { (char, bitmap) ->
            dir.resolve("u%04X.png".format(char.code)).outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Bounding-box sizes of 8-connected components of opaque pixels. */
    private fun inkComponents(bitmap: Bitmap): List<Pair<Int, Int>> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val ink = BooleanArray(w * h) { (pixels[it] ushr 24) >= ALPHA_MIN }
        val seen = BooleanArray(w * h)
        val sizes = mutableListOf<Pair<Int, Int>>()
        val stack = ArrayDeque<Int>()
        for (start in ink.indices) {
            if (!ink[start] || seen[start]) continue
            var area = 0
            var left = w
            var right = -1
            var top = h
            var bottom = -1
            stack.addLast(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                area++
                val x = i % w
                val y = i / w
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = ny * w + nx
                    if (ink[n] && !seen[n]) {
                        seen[n] = true
                        stack.addLast(n)
                    }
                }
            }
            if (area >= MIN_COMPONENT_PX) sizes += (right - left + 1) to (bottom - top + 1)
        }
        return sizes
    }

    private companion object {
        const val FIXTURE = "practice_sheet_scan.jpg"
        const val SCANNER_FIXTURE = "practice_sheet_scanner_output.png"
        const val EXPECTED_GLYPHS = 60
        const val ALPHA_MIN = 40
        const val MIN_COMPONENT_PX = 12
        const val MAX_WIDTH_FACTOR = 2
        const val SPAN_MIN = 0.6f
        const val MIN_ASPECT = 0.1f
        val DOTTED = setOf('i', 'j')
    }
}
