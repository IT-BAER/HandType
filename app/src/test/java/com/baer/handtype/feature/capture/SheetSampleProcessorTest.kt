package com.baer.handtype.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetSampleProcessorTest {

    @Test
    fun aStrokeCrossingTheBorderFromAboveIsInkAboveTheCell() {
        val width = 20
        val borderY = 10
        // Paper 245; a pen stroke at column 5 above the border; a printed guide dot at column 12.
        val luma = IntArray(width * 20) { i ->
            val x = i % width
            val y = i / width
            when {
                x == 5 && y < borderY -> 40
                x == 12 && y == borderY - 4 -> 198
                else -> 245
            }
        }

        val above = SheetSampleProcessor.inkAboveBorder(luma, width, borderY, x0 = 2, x1 = 18)

        assertEquals(16, above.size)
        assertTrue("pen stroke", above[5 - 2])
        assertFalse("printed guide dot", above[12 - 2])
        assertEquals(1, above.count { it })
    }

    @Test
    fun aDescenderCrossingTheBorderIsFollowedIntoTheCellBelow() {
        val width = 30
        val borderY = 20
        // Paper 245; printed border rows 19..21; a descender at column 8 from row 10 to 30 with a
        // hook to column 11 at its end; a letter of the cell below at columns 20..24, rows 26..32.
        val luma = IntArray(width * 40) { i ->
            val x = i % width
            val y = i / width
            when {
                x == 8 && y in 10..30 -> 40
                y == 30 && x in 8..11 -> 40
                x in 20..24 && y in 26..32 -> 40
                y in borderY - 1..borderY + 1 -> 90
                else -> 245
            }
        }

        val stroke = SheetSampleProcessor.strokeBelowBorder(luma, width, borderY, x0 = 2, x1 = 28, seedY = 14, maxY = 36)!!

        fun at(x: Int, y: Int) = stroke[(y - 14) * (28 - 2) + (x - 2)]
        assertTrue("descender below the border", at(8, 28))
        assertTrue("its hook", at(11, 30))
        assertTrue("where it crosses the border", at(8, borderY))
        assertFalse("the border away from the stroke", at(16, borderY))
        assertFalse("the letter of the cell below", at(22, 29))
    }

    @Test
    fun aStrokeEndingAtTheBorderIsNotExtended() {
        val width = 30
        val borderY = 20
        val luma = IntArray(width * 40) { i ->
            val x = i % width
            val y = i / width
            when {
                x == 8 && y in 10..borderY -> 40
                y in borderY - 1..borderY + 1 -> 90
                else -> 245
            }
        }

        assertNull(SheetSampleProcessor.strokeBelowBorder(luma, width, borderY, x0 = 2, x1 = 28, seedY = 14, maxY = 36))
    }

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

    @Test
    fun fiducialsMustOutlineTheSheet() {
        val sheet = SheetSampleProcessor.Fiducials(100f to 100f, 1140f to 100f, 1140f to 1654f, 100f to 1654f)
        val tilted = SheetSampleProcessor.Fiducials(160f to 120f, 1080f to 120f, 1180f to 1640f, 60f to 1640f)
        // Four handwriting strokes picked from a scan cropped to the grid (measured on a user sheet).
        val strokes = SheetSampleProcessor.Fiducials(44f to 68f, 497f to 177f, 347f to 845f, 190f to 850f)

        assertTrue("flat sheet", SheetSampleProcessor.plausibleFiducials(sheet))
        assertTrue("phone tilted towards the top", SheetSampleProcessor.plausibleFiducials(tilted))
        assertFalse("handwriting strokes", SheetSampleProcessor.plausibleFiducials(strokes))
    }

    @Test
    fun guideSnapIgnoresPenStrokeNearTheGuide() {
        val width = 120
        val height = 100
        val luma = IntArray(width * height) { PAPER }
        // A photographed guide: only the core of each dash stays below the guide threshold.
        for (x in 0 until width) if (x % 12 < 4) luma[40 * width + x] = GUIDE
        // The flat top of a 'D': darker and denser than the dashed guide but only over half the cell.
        for (y in 52..55) for (x in 20 until 80) luma[y * width + x] = INK

        assertEquals(40, SheetSampleProcessor.snapLine(luma, width, nominal = 45, limit = height, span = 0 until width, horizontal = true))
    }

    @Test
    fun guideSnapKeepsTheBaselineAboveAnInkedDescenderLine() {
        val cellHeight = 152
        val topInset = 4
        val width = 108
        val height = 145
        fun y(ratio: Float) = (cellHeight * ratio).toInt() - topInset
        val luma = IntArray(width * height) { PAPER }
        // A photographed baseline keeps only thin dash cores; the dotted descender line stays whole.
        for (x in 0 until width) if (x % 12 < 4) luma[y(PracticeSheetGenerator.GUIDE_BASELINE_RATIO) * width + x] = GUIDE
        for (x in 0 until width) if (x % 8 < 2) luma[y(PracticeSheetGenerator.GUIDE_DESCENDER_RATIO) * width + x] = GUIDE
        // The flat bottom of a 'g' loop drawn along the descender line.
        for (yy in y(PracticeSheetGenerator.GUIDE_DESCENDER_RATIO) - 1..y(PracticeSheetGenerator.GUIDE_DESCENDER_RATIO) + 1) {
            for (x in 30 until 75) luma[yy * width + x] = INK
        }
        val baseline = y(PracticeSheetGenerator.GUIDE_BASELINE_RATIO)

        assertEquals(
            baseline,
            SheetSampleProcessor.snapLine(
                luma, width, nominal = baseline + 4, limit = height, span = 0 until width, horizontal = true,
                radius = SheetSampleProcessor.guideSnapRadius(cellHeight),
            ),
        )
    }

    @Test
    fun borderSnapIgnoresLetterStemNearTheBorder() {
        val width = 100
        val height = 160
        val luma = IntArray(width * height) { PAPER }
        for (y in 0 until height) { luma[y * width + 10] = BORDER; luma[y * width + 11] = BORDER }
        // The stem of a 'D' written close to the left border.
        for (y in 40 until 120) for (x in 22..25) luma[y * width + x] = INK

        assertEquals(10, SheetSampleProcessor.snapLine(luma, width, nominal = 18, limit = width, span = 0 until height, horizontal = false))
    }

    @Test
    fun gridLinesAreFoundWhereverTheSheetPrintedThem() {
        // A filled-in sheet whose grid sits closer to the corner squares than the generator's
        // (measured on a user's sheet): columns 133..1109, rows 188..1602.
        val xs = List(9) { 133 + it * 122 }
        val ys = List(9) { 188 + it * 1414 / 8 }
        val luma = IntArray(WIDTH * HEIGHT) { PAPER }
        for (x in xs) for (y in ys.first()..ys.last()) { luma[y * WIDTH + x] = BORDER; luma[y * WIDTH + x + 1] = BORDER }
        for (y in ys) for (x in xs.first()..xs.last()) { luma[y * WIDTH + x] = BORDER; luma[(y + 1) * WIDTH + x] = BORDER }
        for ((top, bottom) in ys.zipWithNext()) {
            val guide = top + (bottom - top) / 3
            for (x in xs.first()..xs.last()) if (x % 12 < 7) luma[guide * WIDTH + x] = GUIDE
        }

        val (foundXs, foundYs) = SheetSampleProcessor.detectGridLines(luma) ?: error("no grid found")

        assertEquals(xs.size, foundXs.size)
        assertEquals(ys.size, foundYs.size)
        // Lines are 2 px thick, so the centre may sit on either pixel.
        xs.zip(foundXs).forEach { (x, f) -> assertTrue("column $x found at $f", f in x..x + 1) }
        ys.zip(foundYs).forEach { (y, f) -> assertTrue("row $y found at $f", f in y..y + 1) }
    }

    @Test
    fun noGridLinesOnBlankPaper() {
        assertEquals(null, SheetSampleProcessor.detectGridLines(IntArray(WIDTH * HEIGHT) { PAPER }))
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
        const val INK = 40
        // Fiducial centres of the generated sheet (outer margin 60 + half of the 80 px fiducial).
        const val GRID_LEFT = 100f
        const val GRID_TOP = 100f
        const val GRID_SIZE_X = WIDTH - 2 * GRID_LEFT
        const val GRID_SIZE_Y = HEIGHT - 2 * GRID_TOP
    }
}
