package com.baer.handtype.feature.capture.stylus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusPagesTest {

    private val tall = listOf(
        InkStroke(listOf(InkPoint(0.3f, 0.15f, 0L), InkPoint(0.4f, 0.45f, 10L), InkPoint(0.5f, 0.75f, 20L))),
    )

    @Test
    fun pagesOfNineCoverSixtyTwoAndSixtyNineCharacters() {
        assertEquals(listOf(0, 9, 18, 27, 36, 45, 54), StylusPages.pageStarts(62, 9))
        assertEquals(62, StylusPages.pageEnd(54, 9, 62))
        assertEquals(8, StylusPages.pageEnd(54, 9, 62) - 54)
        assertEquals(listOf(0, 9, 18, 27, 36, 45, 54, 63), StylusPages.pageStarts(69, 9))
        assertEquals(6, StylusPages.pageEnd(63, 9, 69) - 63)
        assertEquals(9, StylusPages.pageEnd(0, 9, 69))
    }

    @Test
    fun lastPageAndPreviousPage() {
        assertFalse(StylusPages.isLastPage(45, 9, 62))
        assertTrue(StylusPages.isLastPage(54, 9, 62))
        assertTrue(StylusPages.isLastPage(63, 9, 69))
        assertEquals(45, StylusPages.previousStart(54, 9))
        assertEquals(0, StylusPages.previousStart(4, 9))
        assertEquals(0, StylusPages.previousStart(0, 9))
    }

    @Test
    fun cellSlotMapsToRowAndColumn() {
        assertEquals(0, StylusPages.row(0))
        assertEquals(2, StylusPages.column(2))
        assertEquals(1, StylusPages.row(3))
        assertEquals(0, StylusPages.column(3))
        assertEquals(2, StylusPages.row(8))
        assertEquals(2, StylusPages.column(8))
        assertEquals(StylusPages.COLUMNS * 2 + 1, StylusPages.slot(row = 2, column = 1))
    }

    @Test
    fun gridKeepsAspectAndFitsThreeRowsWhenThereIsRoom() {
        // Pixel-sized phone: width-limited, 3 rows.
        val wide = StylusPages.grid(widthDp = 372f, heightDp = 520f, aspectRatio = 0.75f)
        assertEquals(3, wide.rows)
        assertEquals(9, wide.pageSize)
        assertEquals((372f - 16f) / 3f / 0.75f, wide.cellHeightDp, 0.01f)
        // Height-limited but still at least 88 dp.
        val short = StylusPages.grid(widthDp = 372f, heightDp = 300f, aspectRatio = 0.75f)
        assertEquals(3, short.rows)
        assertEquals((300f - 16f) / 3f, short.cellHeightDp, 0.01f)
    }

    @Test
    fun gridFallsBackToTwoRowsBelowTheMinimumCellHeight() {
        val grid = StylusPages.grid(widthDp = 372f, heightDp = 260f, aspectRatio = 0.75f)
        assertEquals(2, grid.rows)
        assertEquals(6, grid.pageSize)
        assertEquals((260f - 8f) / 2f, grid.cellHeightDp, 0.01f)
    }

    @Test
    fun lastWrittenFollowsStrokeOrderAndSkipsEmptiedCells() {
        val counts = mutableMapOf(9 to 2, 11 to 1, 12 to 0)
        val order = listOf(9, 11, 12, 9, 3)
        // 3 is on another page, 12 was cleared: the newest stroke on the page is in cell 9.
        assertEquals(9, StylusPages.lastWritten(order, 9, 18) { counts[it] ?: 0 })
        assertEquals(11, StylusPages.lastWritten(listOf(9, 11), 9, 18) { counts[it] ?: 0 })
    }

    @Test
    fun lastWrittenWithoutOrderFallsBackToLastNonEmptyCell() {
        val counts = mapOf(9 to 1, 13 to 2)
        assertEquals(13, StylusPages.lastWritten(emptyList(), 9, 18) { counts[it] ?: 0 })
        assertNull(StylusPages.lastWritten(emptyList(), 0, 9) { counts[it] ?: 0 })
    }

    @Test
    fun checkPageRunsShapeThenRecognitionPerCell() = runBlocking {
        val characters = listOf('A', 'B', 'C', 'D')
        val glyphs = listOf(tall, emptyList(), tall, tall)
        val seen = mutableListOf<Int>()
        val answers = mapOf(0 to "A", 2 to "X", 3 to "D")
        var call = 0
        val order = listOf(0, 2, 3)
        val check = StylusPages.checkPage(
            characters = characters,
            glyphs = glyphs,
            start = 0,
            end = 4,
            verified = emptySet(),
            armed = emptySet(),
        ) { _ ->
            val i = order[call++]
            seen += i
            listOf(answers.getValue(i))
        }
        // The empty cell never reaches recognition; recognition runs in cell order.
        assertEquals(listOf(0, 2, 3), seen)
        assertEquals(PrecheckResult.Pass, check.results[0])
        assertEquals(PrecheckResult.Empty, check.results[1])
        assertEquals(PrecheckResult.WrongLetter("X"), check.results[2])
        assertEquals(false, check.recognizerUnavailable)

        val review = StylusPages.review(check.results)
        assertEquals(listOf(1, 2), review.failing)
        assertEquals(setOf(2), review.armed)
        assertEquals(setOf(0, 3), review.passed)
    }

    @Test
    fun armedAndVerifiedCellsSkipRecognition() = runBlocking {
        val characters = listOf('A', 'B', 'C')
        val glyphs = listOf(tall, tall, tall)
        var calls = 0
        val check = StylusPages.checkPage(characters, glyphs, 0, 3, verified = setOf(0), armed = setOf(1)) {
            calls++
            listOf("C")
        }
        assertEquals(1, calls)
        assertFalse(0 in check.results)
        assertEquals(PrecheckResult.Pass, check.results[1])
        assertEquals(PrecheckResult.Pass, check.results[2])
        assertTrue(StylusPages.review(check.results).failing.isEmpty())
    }

    @Test
    fun armedCellStillFailsTheShapeCheck() = runBlocking {
        val check = StylusPages.checkPage(listOf('A'), listOf(emptyList()), 0, 1, emptySet(), armed = setOf(0)) {
            error("no recognition for a shape failure")
        }
        assertEquals(PrecheckResult.Empty, check.results[0])
        assertNull(check.recognizerUnavailable)
    }

    @Test
    fun unavailableRecognizerFallsBackToShapeChecks() = runBlocking {
        val check = StylusPages.checkPage(listOf('A', 'B'), listOf(tall, tall), 0, 2, emptySet(), emptySet()) { null }
        assertEquals(true, check.recognizerUnavailable)
        assertTrue(StylusPages.review(check.results).failing.isEmpty())
    }
}
