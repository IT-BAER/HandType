package com.baer.handtype.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetSampleProcessorTest {

    @Test
    fun paperMaskCoversInkEnclosedByThePage() {
        val width = 20
        val height = 20
        // Bright page 4..15 with a dark 3x3 fiducial at 6..8 and dark table outside.
        val bright = BooleanArray(width * height) { i ->
            val x = i % width
            val y = i / width
            x in 4..15 && y in 4..15 && !(x in 6..8 && y in 6..8)
        }

        val filled = SheetSampleProcessor.fillEnclosedHoles(bright, width, height)

        assertTrue("fiducial pixel belongs to the page", filled[7 * width + 7])
        assertTrue("page pixel kept", filled[10 * width + 10])
        assertFalse("table outside the page stays excluded", filled[2 * width + 2])
        assertFalse("table touching the frame edge stays excluded", filled[0])
    }

    @Test
    fun brightCutoffKeepsShadowedPaperAndRejectsWood() {
        // Wood peak at 119, paper peak at 180, Otsu between them at 139 (measured on a phone photo).
        val histogram = IntArray(256).also { it[119] = 40_000; it[180] = 60_000; it[165] = 5_000 }

        val (cutoff, peak) = SheetSampleProcessor.pageBrightCutoff(histogram, otsu = 139)

        assertTrue("paper peak found", peak == 180)
        assertTrue("shadowed paper at 165 counts as page (cutoff $cutoff)", cutoff <= 165)
        assertTrue("wood at 119 stays out (cutoff $cutoff)", cutoff > 119)
    }

    @Test
    fun gridRowsAreReadFromThePrintedBorders() {
        for (rows in listOf(PracticeSheetGenerator.LEGACY_GRID_ROWS, PracticeSheetGenerator.GRID_ROWS)) {
            assertEquals("$rows-row sheet", rows, SheetSampleProcessor.detectGridRows(rectifiedSheet(rows)))
        }
    }

    @Test
    fun gridRowsSurviveRectificationBow() {
        // Borders drift by up to 15 px towards the bottom of a photographed page.
        for (rows in listOf(PracticeSheetGenerator.LEGACY_GRID_ROWS, PracticeSheetGenerator.GRID_ROWS)) {
            val sheet = rectifiedSheet(rows) { y -> y + 15 * y / HEIGHT }
            assertEquals("$rows-row sheet", rows, SheetSampleProcessor.detectGridRows(sheet))
        }
    }

    /** Luma of a rectified blank sheet: paper, solid cell borders and dashed guides at the generator's rows. */
    private fun rectifiedSheet(rows: Int, bow: (Int) -> Int = { it }): IntArray {
        val luma = IntArray(WIDTH * HEIGHT) { PAPER }
        val cells = PracticeSheetGenerator.normalizedCells(PracticeSheetGenerator.cellCharacters(rows = rows))
        val (left, right) = (GRID_LEFT + cells.first().left * GRID_SIZE_X).toInt() to
            (GRID_LEFT + cells.last().right * GRID_SIZE_X).toInt()
        fun row(y: Int, value: Int, dashed: Boolean) {
            val by = bow(y)
            for (x in left until right) if (!dashed || (x - left) % 12 < 7) luma[by * WIDTH + x] = value
        }
        cells.map { it.top to it.bottom }.distinct().forEach { (top, bottom) ->
            val y0 = GRID_TOP + top * GRID_SIZE_Y
            val y1 = GRID_TOP + bottom * GRID_SIZE_Y
            row(y0.toInt(), BORDER, dashed = false)
            row(y0.toInt() + 1, BORDER, dashed = false)
            row(y1.toInt(), BORDER, dashed = false)
            row((y0 + (y1 - y0) * PracticeSheetGenerator.GUIDE_UPPER_RATIO).toInt(), GUIDE, dashed = true)
            row((y0 + (y1 - y0) * PracticeSheetGenerator.GUIDE_BASELINE_RATIO).toInt(), GUIDE, dashed = true)
        }
        return luma
    }

    private companion object {
        const val WIDTH = 1240
        const val HEIGHT = 1754
        const val PAPER = 253
        const val BORDER = 197
        const val GUIDE = 206
        // Fiducial centres of the generated sheet (outer margin 60 + half of the 80 px fiducial).
        const val GRID_LEFT = 100f
        const val GRID_TOP = 100f
        const val GRID_SIZE_X = WIDTH - 2 * GRID_LEFT
        const val GRID_SIZE_Y = HEIGHT - 2 * GRID_TOP
    }
}
