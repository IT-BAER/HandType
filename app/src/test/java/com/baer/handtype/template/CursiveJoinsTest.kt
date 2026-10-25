package com.baer.handtype.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursiveJoinsTest {

    private val width = 40

    /** Ink set by [ink] on a 40 x 60 canvas; x-height band rows 20..49. */
    private fun glyph(ink: (x: Int, y: Int) -> Boolean) = IntArray(width * 60) { i -> if (ink(i % width, i / width)) 255 else 0 }

    // A cursive u-like letter: body columns 16..30 over the band, a lead-in rising from (4, 49) to the
    // body at (16, 37), an exit tail from (30, 49) up to (36, 40).
    private val cursive = glyph { x, y ->
        (y in 20..49 && (x in 16..17 || x in 29..30)) || (y == 49 && x in 16..30) ||
            (x in 4..16 && y == 49 - (x - 4)) || (x in 30..36 && y == 49 - (x - 30) * 9 / 6)
    }

    @Test
    fun aCursiveLetterHasALeadInFromTheBaselineAndAnExitTip() {
        val tips = CursiveJoins.tips(cursive, width, bandTop = 20, bandBottom = 49)!!
        assertTrue("lead-in", tips.hasLeadIn)
        assertEquals("entry tip", 4 to 49, tips.entryX to tips.entryY)
        assertEquals("exit tip", 36 to 40, tips.exitX to tips.exitY)
        assertEquals("body left", 16, tips.bodyLeft)
    }

    @Test
    fun theLeadInIsTheInkLeftOfTheBody() {
        val leadIn = CursiveJoins.leadInPixels(cursive, width, 20, 49, bodyLeft = 16)
        assertTrue(leadIn.all { it % width < 16 })
        assertEquals(12, leadIn.size)
    }

    @Test
    fun aPrintLetterHasNoLeadIn() {
        // A print n: two stems and an arch, nothing reaching left of the stem.
        val n = glyph { x, y -> (y in 20..49 && (x in 10..11 || x in 26..27)) || (y == 22 && x in 10..27) }
        assertFalse(CursiveJoins.tips(n, width, bandTop = 20, bandBottom = 49)!!.hasLeadIn)
    }

    @Test
    fun aPrintXOrZHasInkAboveLeftOfTheBody() {
        // Their lower left reaches far left of the mid-band ink like a lead-in, but so does their top.
        val x = glyph { x, y -> y in 20..49 && (x - (8 + (y - 20) * 24 / 29) in 0..1 || x - (32 - (y - 20) * 24 / 29) in 0..1) }
        val z = glyph { x, y -> ((y == 20 || y == 49) && x in 8..33) || (y in 20..49 && x - (32 - (y - 20) * 24 / 29) in 0..1) }
        assertTrue("x", CursiveJoins.tips(x, width, bandTop = 20, bandBottom = 49)!!.inkAboveLeft)
        assertTrue("z", CursiveJoins.tips(z, width, bandTop = 20, bandBottom = 49)!!.inkAboveLeft)
        assertFalse("cursive u", CursiveJoins.tips(cursive, width, bandTop = 20, bandBottom = 49)!!.inkAboveLeft)
    }

    @Test
    fun theLeadInMeetsTheBodyAtItsTopRightEnd() {
        val leadIn = CursiveJoins.leadInPixels(cursive, width, 20, 49, bodyLeft = 16)
        assertEquals(15 to 38, CursiveJoins.leadInTop(leadIn, width)!!.let { it % width to it / width })
    }

    @Test
    fun theBodyFrontIsTheBandInkRightOfTheLeadIn() {
        val front = CursiveJoins.bodyFront(cursive, width, 20, 49, bodyLeft = 16)
        assertTrue(front.isNotEmpty())
        assertTrue(front.all { it % width in 16 until 26 && it / width in 20..49 })
    }

    @Test
    fun inkAlphaIsTheMedianOfTheStrokeCore() {
        assertEquals(230, CursiveJoins.inkAlpha(intArrayOf(0, 60, 100, 200, 230, 250, 0)))
    }

    @Test
    fun anExitHighInTheBandIsNotTheTail() {
        // The cursive u with a dot-like speck at the band's top right, as a j dot can sit.
        val dotted = cursive.copyOf().also { a -> for (y in 21..22) for (x in 37..38) a[y * width + x] = 255 }
        val tips = CursiveJoins.tips(dotted, width, bandTop = 20, bandBottom = 49)!!
        assertEquals(36 to 40, tips.exitX to tips.exitY)
    }

    @Test
    fun theTailPointsUpAndRight() {
        val (dx, dy) = CursiveJoins.tailDirection(cursive, width, exitX = 36, exitY = 40, radius = 6f)
        assertTrue("right $dx", dx > 0.3f)
        assertTrue("up $dy", dy < -0.5f)
    }

    @Test
    fun penWidthIsTheStrokeWidth() {
        // A 6 px wide upright bar, 30 rows long; its short ends count as boundary too.
        val bar = glyph { x, y -> x in 10..15 && y in 10..39 }
        assertEquals(6f, CursiveJoins.penWidth(bar, width), 1f)
    }


    @Test
    fun aHandIsCursiveWhenMostLettersHaveALeadIn() {
        assertTrue(CursiveJoins.isCursiveHand(listOf(true, true, true, false, true, true)))
        assertFalse(CursiveJoins.isCursiveHand(listOf(true, false, false, true, false, false)))
        assertFalse("too few letters to tell", CursiveJoins.isCursiveHand(listOf(true, true)))
    }

    @Test
    fun lettersThatEndHighOrCapitalsDoNotJoin() {
        assertTrue(CursiveJoins.joins('e', 'l'))
        assertFalse(CursiveJoins.joins('o', 'n'))
        assertFalse(CursiveJoins.joins('H', 'e'))
        assertFalse(CursiveJoins.joins('a', '.'))
    }
}
