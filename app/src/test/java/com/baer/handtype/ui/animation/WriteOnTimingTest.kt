package com.baer.handtype.ui.animation

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Rect
import com.baer.handtype.template.InkRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteOnTimingTest {

    @Test fun firstLineWaitsThenWritesInAQuarterSecond() {
        assertEquals(0f, NoteWriteOn.lineProgress(index = 0, lineCount = 8, elapsedMillis = 0), 0f)
        assertEquals(0f, NoteWriteOn.lineProgress(index = 0, lineCount = 8, elapsedMillis = 175), 0f)
        assertEquals(0.5f, NoteWriteOn.lineProgress(index = 0, lineCount = 8, elapsedMillis = 300), 0.001f)
        assertEquals(1f, NoteWriteOn.lineProgress(index = 0, lineCount = 8, elapsedMillis = 425), 0f)
    }

    @Test fun shortNotesStartALineEvery260Millis() {
        assertEquals(0f, NoteWriteOn.lineProgress(index = 1, lineCount = 8, elapsedMillis = 435), 0f)
        assertEquals(0.5f, NoteWriteOn.lineProgress(index = 1, lineCount = 8, elapsedMillis = 560), 0.001f)
        assertEquals(1f, NoteWriteOn.lineProgress(index = 7, lineCount = 8, elapsedMillis = 2245), 0f)
        assertEquals(2245, NoteWriteOn.totalMillis(lineCount = 8))
        assertEquals(260, NoteWriteOn.strideMillis(lineCount = 10))
    }

    @Test fun longNotesFinishWithinTheCap() {
        for (lines in listOf(11, 20, 60)) {
            assertTrue("$lines lines", NoteWriteOn.totalMillis(lines) <= 2800)
            assertTrue("$lines lines", NoteWriteOn.strideMillis(lines) < 260)
            assertEquals(1f, NoteWriteOn.lineProgress(lines - 1, lines, NoteWriteOn.totalMillis(lines).toLong()), 0f)
        }
    }

    @Test fun singleLineNoteTakesDelayPlusOneLine() {
        assertEquals(425, NoteWriteOn.totalMillis(lineCount = 1))
    }

    // Line 0's descender crosses into band 1 right of band 1's own ink.
    private val crossingRows = listOf(
        InkRow(10, 0, 100, 85, inkTop = 20, inkBottom = 90),
        InkRow(10, 85, 60, 180, inkTop = 80, inkBottom = 140),
    )

    @Test fun finishedNoteRevealsNeighbourInkOutsideABandsSpan() {
        assertEquals(
            listOf(Rect(10f, 0f, 100f, 85f), Rect(60f, 85f, 100f, 90f), Rect(10f, 85f, 60f, 180f)),
            NoteWriteOn.revealBoxes(crossingRows, elapsedMillis = 2000),
        )
    }

    @Test fun crossingInkIsWipedWithItsOwnLine() {
        assertEquals(listOf(Rect(10f, 0f, 55f, 85f)), NoteWriteOn.revealBoxes(crossingRows, elapsedMillis = 300))
        assertEquals(
            listOf(Rect(10f, 0f, 82f, 85f), Rect(60f, 85f, 82f, 90f)),
            NoteWriteOn.revealBoxes(crossingRows, elapsedMillis = 375),
        )
        assertEquals(emptyList<Rect>(), NoteWriteOn.revealBoxes(crossingRows, elapsedMillis = 175))
    }

    @Test fun oldScreenStaysOpaqueUntilTheGeneratingLayerIsIn() {
        val spec = PhaseFade.holdOldLayer.vectorize(Float.VectorConverter)
        val alpha = { millis: Long ->
            spec.getValueFromNanos(millis * 1_000_000, AnimationVector1D(1f), AnimationVector1D(0f), AnimationVector1D(0f)).value
        }
        assertEquals(1f, alpha(0), 0f)
        assertEquals(1f, alpha(179), 0f)
        assertEquals(0f, alpha(181), 0f)
    }

    @Test fun penReachesEightyTwoPercentAfterThePromoPass() {
        assertEquals(0f, PenPass.progress(0), 0f)
        assertEquals(0.41f, PenPass.progress(595), 0.001f)
        assertEquals(0.82f, PenPass.progress(1190), 0.001f)
    }

    @Test fun resultArrivesWhileThePenIsAt81Percent() {
        assertEquals(1090, PenPass.RESULT_AT_MILLIS)
        assertEquals(0.808f, PenPass.progress(PenPass.RESULT_AT_MILLIS.toLong()), 0.002f)
    }

    @Test fun slowRenderFinishesThePathThenLoops() {
        var previous = 0f
        for (t in 0L..1590L step 10) {
            val p = PenPass.progress(t)
            assertTrue("t=$t p=$p < $previous", p >= previous)
            previous = p
        }
        assertEquals(1f, PenPass.progress(1590), 0.001f)
        assertTrue(PenPass.progress(1700) < 0.2f)
        assertEquals(PenPass.progress(1700), PenPass.progress(1700 + PenPass.LOOP_MILLIS.toLong()), 0.0001f)
    }
}
