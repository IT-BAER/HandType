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
    fun theLeadInIsTracedRowByRowFromThePenDownPointIntoTheBody() {
        val runs = CursiveJoins.leadInRuns(cursive, width, entryX = 4, entryY = 49, bandTop = 20, maxRunPx = 8f).runs
        assertEquals("starts at the pen-down point", listOf(49, 4, 4), runs.first().toList())
        assertEquals("one run per row, upward", (49 downTo 49 - runs.size + 1).toList(), runs.map { it[0] })
        assertEquals(listOf(40, 13, 13), runs.first { it[0] == 40 }.toList())
        assertEquals("merges into the stem", listOf(37, 16, 17), runs.first { it[0] == 37 }.toList())
    }

    @Test
    fun theLeadInTraceStopsWhereTheStrokeForksOrWidens() {
        // A second stroke leaves the lead-in at row 44, (8, 44) up to (8, 39).
        val forked = cursive.copyOf().also { a -> for (y in 39..44) a[y * width + 8] = 255 }
        assertEquals(44, CursiveJoins.leadInRuns(forked, width, 4, 49, 20, 8f).runs.last()[0])
        // A bar 12 px wide crosses the lead-in at row 45.
        val barred = cursive.copyOf().also { a -> for (x in 2..13) a[45 * width + x] = 255 }
        assertEquals(46, CursiveJoins.leadInRuns(barred, width, 4, 49, 20, 8f).runs.last()[0])
        // The letter's own downstroke, 5 px wide, meets the lead-in at row 44: the lead-in's own ink ends
        // there, the trace goes on up the shared stroke to row 38; at row 37 bar and stem stand apart.
        val crossed = cursive.copyOf().also { a -> for (y in 30..44) for (x in 10..14) a[y * width + x] = 255 }
        val leadIn = CursiveJoins.leadInRuns(crossed, width, 4, 49, 20, 8f)
        assertEquals("own ink", 45, leadIn.runs[leadIn.ownRuns - 1][0])
        assertEquals("shared stroke", 38, leadIn.runs.last()[0])
    }

    @Test
    fun aPointedPenDownStartIsNotAMerge() {
        // A lead-in that starts as a 1 px point at (4, 49), is 2 px wide one row up and 6 px from then on.
        val pointed = glyph { x, y -> y in 30..49 && x - (4 + (49 - y)) in 0..listOf(0, 1).getOrElse(49 - y) { 5 } }
        val leadIn = CursiveJoins.leadInRuns(pointed, width, 4, 49, 20, maxRunPx = 8f, settleRows = 4)
        assertEquals(leadIn.runs.size, leadIn.ownRuns)
    }

    @Test
    fun theTailIsTracedFromItsEndBackDownTheStroke() {
        // A 3 px wide tail rising at 45 degrees from (20, 49) to its end at (37, 34).
        val tail = glyph { x, y -> y in 34..49 && x - (20 + (49 - y)) in 0..2 }
        val runs = CursiveJoins.tailRuns(tail, width, exitX = 37, exitY = 34, bandBottom = 49, maxRunPx = 8f)
        assertEquals(listOf(34, 35, 37), runs.first().toList())
        assertEquals((34..49).toList(), runs.map { it[0] })
        // The exit point sits below the band's top, but the tail's tip rises above it: the tip comes first.
        val fromBelowTip = CursiveJoins.tailRuns(tail, width, exitX = 35, exitY = 36, bandBottom = 49, maxRunPx = 8f)
        assertEquals((34..49).toList(), fromBelowTip.map { it[0] })
    }

    @Test
    fun anExitWhoseStrokeTurnsBackLeftAboveItIsNoTail() {
        // The right side of an s bowl: a 3 px stroke from (18, 22) down-right to (30, 40), then down-left to (20, 49).
        val bowl = glyph { x, y ->
            (y in 22..40 && x - (18 + (y - 22) * 12 / 18) in 0..2) || (y in 40..49 && x - (30 - (y - 40) * 10 / 9) in 0..2)
        }
        assertEquals(emptyList<IntArray>(), CursiveJoins.tailRuns(bowl, width, exitX = 32, exitY = 40, bandBottom = 49, maxRunPx = 8f))
    }

    @Test
    fun aStrokesProfileIsItsCoreDarknessAndHalfDarkWidth() {
        // Soft rows: 40 120 200 120 40 across, one row darker.
        val soft = IntArray(width * 10).also { a ->
            for (y in 0..9) listOf(40, 120, 200, 120, 40).forEachIndexed { k, v -> a[y * width + 10 + k] = if (y == 5) 255 else v }
        }
        val runs = (0..9).map { intArrayOf(it, 10, 14) }
        assertEquals(200 to 3f, CursiveJoins.strokeProfile(soft, width, runs, direction = 0f to 1f))
    }

    @Test
    fun aFlatStrokesWidthIsMeasuredDownItsColumns() {
        // A level stroke along x 5..30, soft rows 40 120 200 120 40 down; each row run spans its whole length.
        val flat = IntArray(width * 20).also { a ->
            for (x in 5..30) listOf(40, 120, 200, 120, 40).forEachIndexed { k, v -> a[(10 + k) * width + x] = v }
        }
        val runs = (10..14).map { intArrayOf(it, 5, 30) }
        assertEquals(200 to 3f, CursiveJoins.strokeProfile(flat, width, runs, direction = 1f to 0f))
    }

    // A tail rising at 45 degrees to its end at (10, -10), as centres from the end back.
    private val tailLine = (0..10).map { (10 - it).toFloat() to -(10 - it).toFloat() }

    @Test
    fun aLeadInOnTheTailsLineJoinsWithoutCuttingEither() {
        val leadIn = (0..10).map { (12 + it).toFloat() to -(12 + it).toFloat() }
        assertEquals(0 to 0, CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2))
    }

    @Test
    fun aLeadInThatEndsBelowTheTailsEndCutsTheTailDownToIt() {
        // Parallel to the tail, 8 px to the right, ending at (12, -4).
        val leadIn = (0..4).map { (8 + it).toFloat() to -it.toFloat() }
        val (i, _) = CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2)!!
        assertTrue("tail cut at ${tailLine[i]}", tailLine[i].second >= -4f)
    }

    @Test
    fun aSteepLeadInIsJoinedWhereItRisesPastTheTailNotBelowIt() {
        // The tail ends at (10, -10); a lead-in rising steeply from (14, 2) to (20, -28).
        val leadIn = (0..30).map { (14 + it / 5f) to (2 - it).toFloat() }
        val (i, j) = CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2)!!
        assertTrue("lead-in cut at ${leadIn[j]}, tail cut at ${tailLine[i]}", leadIn[j].second <= tailLine[i].second)
    }

    @Test
    fun aLeadInOnlyReachedByASharpTurnDoesNotJoin() {
        // Above the tail's end, rising up and to the left at 130 degrees: a bridge within 40 degrees of the
        // 45 degree tail points right of straight up, one within 40 degrees of the lead-in points left of it.
        val leadIn = (0..10).map { (11 - it * 0.643f) to (-20 - it * 0.766f) }
        assertEquals(null, CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2))
    }

    @Test
    fun aLeadInBehindTheTailDoesNotJoin() {
        val leadIn = (0..10).map { (-20 + it).toFloat() to -it.toFloat() }
        assertEquals(null, CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2))
    }


    @Test
    fun anExitHighInTheBandIsNotTheTail() {
        // The cursive u with a dot-like speck at the band's top right, as a j dot can sit.
        val dotted = cursive.copyOf().also { a -> for (y in 21..22) for (x in 37..38) a[y * width + x] = 255 }
        val tips = CursiveJoins.tips(dotted, width, bandTop = 20, bandBottom = 49)!!
        assertEquals(36 to 40, tips.exitX to tips.exitY)
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
