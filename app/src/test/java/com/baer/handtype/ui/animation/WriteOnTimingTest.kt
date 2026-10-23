package com.baer.handtype.ui.animation

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
