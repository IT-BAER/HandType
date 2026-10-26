package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.capture.PracticeSheetGenerator
import com.baer.handtype.feature.capture.SheetSampleProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A pen stroke inside a printed label's box but clear of the label letter itself belongs to the
 * written letter: the scan keeps it, and follows a descender through it into the cell below.
 */
@RunWith(AndroidJUnit4::class)
class SheetLabelOverlapTest {

    private val cells = PracticeSheetGenerator.cellCharacters()
    private val layout = PracticeSheetGenerator.fiducialLayout
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    @Test
    fun aStrokeStartingBesideTheLabelLetterIsKept() {
        // The L label leaves its box's upper right empty; the stroke starts there and leaves the
        // box to the right above the L's foot. The same stroke in the K cell starts clear of its label.
        val l = PracticeSheetGenerator.labelBounds('L')
        val sheet = sheet { canvas ->
            fun stroke(c: Char, startX: Float) {
                val (x0, y0) = cellOrigin(c)
                val top = y0 + l.top + 2f
                canvas.drawLine(x0 + startX, top, x0 + startX + 40f, y0 + baselineOffset(), pen)
            }
            stroke('L', l.left + 10f)
            stroke('K', l.left + 40f)
        }
        val glyphs = SheetSampleProcessor.process(sheet, skipPageDetection = true).glyphs
        val beside = inkRows(glyphs.getValue('L'))
        val clear = inkRows(glyphs.getValue('K'))
        assertTrue("stroke beside the label ${beside.size} rows, clear of it ${clear.size}", beside.size >= clear.size - 2)
    }

    @Test
    fun aPenStrokeOverTheLabelLetterIsKept() {
        // A dark L written with its stem on the right stem of the H label; the same L in the K cell clear of its label.
        val h = PracticeSheetGenerator.labelBounds('H')
        val sheet = sheet { canvas ->
            fun stem(c: Char, x: Float) {
                val (x0, y0) = cellOrigin(c)
                canvas.drawLine(x0 + x, y0 + h.top + 2f, x0 + x, y0 + baselineOffset(), pen)
                canvas.drawLine(x0 + x, y0 + baselineOffset(), x0 + x + 30f, y0 + baselineOffset(), pen)
            }
            stem('H', h.right - 2f)
            stem('K', h.right + 30f)
        }
        val glyphs = SheetSampleProcessor.process(sheet, skipPageDetection = true).glyphs
        val over = inkRows(glyphs.getValue('H'))
        val clear = inkRows(glyphs.getValue('K'))
        assertTrue("stem over the label ${over.size} rows, clear of it ${clear.size}", over.size >= clear.size - 2)
    }

    @Test
    fun aPieceOfStrokeCutOffByTheLabelLetterIsDropped() {
        // The stroke starts in the L label's empty upper right and runs down through the L's foot:
        // erasing the foot leaves its start as a loose piece inside the label box.
        val l = PracticeSheetGenerator.labelBounds('L')
        val sheet = sheet { canvas ->
            val (x0, y0) = cellOrigin('L')
            canvas.drawLine(x0 + l.left + 10f, y0 + l.top + 4f, x0 + l.left + 10f, y0 + baselineOffset(), pen)
        }
        val glyph = SheetSampleProcessor.process(sheet, skipPageDetection = true).glyphs.getValue('L')
        assertTrue("the stroke and ${components(glyph) - 1} loose piece(s)", components(glyph) == 1)
    }

    @Test
    fun aDescenderPassingBesideTheLabelBelowIsFollowed() {
        // D sits above L, C above K: each descender ends in the box of the label below, the D's beside
        // the L letter, the C's clear of the K label.
        val l = PracticeSheetGenerator.labelBounds('L')
        val sheet = sheet { canvas ->
            fun descender(c: Char, x: Float) {
                val (x0, y0) = cellOrigin(c)
                val cellHeight = cellOrigin(cells[cells.indexOf(c) + PracticeSheetGenerator.GRID_COLS]).second - y0
                canvas.drawLine(x0 + x, y0 + baselineOffset() * 0.5f, x0 + x, y0 + cellHeight + l.top + 10f, pen)
            }
            descender('D', l.left + 11f)
            descender('C', l.left + 40f)
        }
        val glyphs = SheetSampleProcessor.process(sheet, skipPageDetection = true).glyphs
        val beside = inkRows(glyphs.getValue('D')).last()
        val clear = inkRows(glyphs.getValue('C')).last()
        assertTrue("descender beside the label ends on row $beside, clear of it on row $clear", beside >= clear - 2)
    }

    private fun sheet(draw: (Canvas) -> Unit): Bitmap =
        PracticeSheetGenerator.renderBitmap(cells).copy(Bitmap.Config.ARGB_8888, true).also { draw(Canvas(it)) }

    private fun cellOrigin(c: Char): Pair<Float, Float> {
        val cell = PracticeSheetGenerator.normalizedCells(cells)[cells.indexOf(c)]
        val gridLeft = layout.topLeft.first
        val gridTop = layout.topLeft.second
        return (gridLeft + cell.left * (layout.topRight.first - gridLeft)) to (gridTop + cell.top * (layout.bottomLeft.second - gridTop))
    }

    private fun baselineOffset(): Float {
        val cell = PracticeSheetGenerator.normalizedCells(cells)[0]
        return (cell.bottom - cell.top) * (layout.bottomLeft.second - layout.topLeft.second) * PracticeSheetGenerator.GUIDE_BASELINE_RATIO
    }

    private fun components(glyph: Bitmap): Int {
        val w = glyph.width
        val ink = BooleanArray(w * glyph.height) { glyph.getPixel(it % w, it / w) ushr 24 >= 40 }
        var count = 0
        for (start in ink.indices) {
            if (!ink[start]) continue
            count++
            val stack = ArrayDeque(listOf(start))
            ink[start] = false
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                for (dy in -1..1) for (dx in -1..1) {
                    val x = i % w + dx
                    val y = i / w + dy
                    if (x !in 0 until w || y !in 0 until glyph.height || !ink[y * w + x]) continue
                    ink[y * w + x] = false
                    stack.addLast(y * w + x)
                }
            }
        }
        return count
    }

    private fun inkRows(glyph: Bitmap): List<Int> =
        (0 until glyph.height).filter { y -> (0 until glyph.width).any { x -> glyph.getPixel(x, y) ushr 24 >= 40 } }
}
