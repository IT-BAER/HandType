package com.baer.handtype.template

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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
    fun aStrokeTouchingTheLeadInEndsItsOwnInkThoughTheRunHardlyWidens() {
        // A second stroke 2 px wide crosses rows 40..44 one pixel right of the lead-in, a soft valley between.
        val touched = cursive.copyOf().also { a ->
            for (y in 40..44) {
                val x = 53 - y
                a[y * width + x + 1] = 60
                a[y * width + x + 2] = 255
                a[y * width + x + 3] = 255
            }
        }
        val leadIn = CursiveJoins.leadInRuns(touched, width, 4, 49, 20, 8f)
        assertEquals("own ink", 45, leadIn.runs[leadIn.ownRuns - 1][0])
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
        val runs = CursiveJoins.tailRuns(tail, width, exitX = 37, exitY = 34, bandTop = 20, bandBottom = 49, maxRunPx = 8f, penPx = 3f)
        assertEquals(listOf(34, 35, 37), runs.first().toList())
        assertEquals((34..49).toList(), runs.map { it[0] })
        // The exit point sits below the band's top, but the tail's tip rises above it: the tip comes first.
        val fromBelowTip = CursiveJoins.tailRuns(tail, width, exitX = 35, exitY = 36, bandTop = 20, bandBottom = 49, maxRunPx = 8f, penPx = 3f)
        assertEquals((34..49).toList(), fromBelowTip.map { it[0] })
    }

    @Test
    fun anExitOnAStemRisingAboveTheBandIsNoTail() {
        // A capital's right leg: a 3 px stem from its foot at (30, 49) up past the band's top at row 20 to row 5.
        val leg = glyph { x, y -> y in 5..49 && x in 28..30 }
        assertEquals(emptyList<IntArray>(), CursiveJoins.tailRuns(leg, width, exitX = 30, exitY = 40, bandTop = 20, bandBottom = 49, maxRunPx = 8f, penPx = 3f))
    }

    @Test
    fun aBlobAtAStemsFootIsNoTail() {
        // A blob 12 px wide and 6 rows tall, on its own like the ink spot at a capital's foot.
        val blob = glyph { x, y -> y in 41..46 && x in 10..21 }
        assertEquals(emptyList<IntArray>(), CursiveJoins.tailRuns(blob, width, exitX = 21, exitY = 44, bandTop = 20, bandBottom = 49, maxRunPx = 15f, penPx = 3f))
        // A tail rising at 45 degrees is as wide across as the pen, but 1.4 pens along each row.
        val tail = glyph { x, y -> y in 34..49 && x - (20 + (49 - y)) in 0..2 }
        assertEquals(16, CursiveJoins.tailRuns(tail, width, exitX = 37, exitY = 34, bandTop = 20, bandBottom = 49, maxRunPx = 15f, penPx = 2.2f).size)
    }

    @Test
    fun anExitWhoseStrokeTurnsBackLeftAboveItIsNoTail() {
        // The right side of an s bowl: a 3 px stroke from (18, 22) down-right to (30, 40), then down-left to (20, 49).
        val bowl = glyph { x, y ->
            (y in 22..40 && x - (18 + (y - 22) * 12 / 18) in 0..2) || (y in 40..49 && x - (30 - (y - 40) * 10 / 9) in 0..2)
        }
        assertEquals(emptyList<IntArray>(), CursiveJoins.tailRuns(bowl, width, exitX = 32, exitY = 40, bandTop = 20, bandBottom = 49, maxRunPx = 8f, penPx = 3f))
    }

    @Test
    fun aStrokesCrossSectionIsItsAlphaAcrossItInHalfPixelSteps() {
        // Soft rows: 40 120 200 120 40 across x 10..14, one row darker.
        val soft = IntArray(width * 10).also { a ->
            for (y in 0..9) listOf(40, 120, 200, 120, 40).forEachIndexed { k, v -> a[y * width + 10 + k] = if (y == 5) 255 else v }
        }
        val section = CursiveJoins.crossSection(soft, width, points = (0..9).map { 12f to it.toFloat() }, direction = 0f to 1f)
        assertEquals(listOf(200f, 160f, 120f, 80f, 40f, 20f, 0f), section.take(7))
    }

    @Test
    fun aFlatStrokesCrossSectionIsTakenAcrossItNotAlongItsRows() {
        // A level stroke along x 5..30, soft rows 40 120 200 120 40 down; each row's run centre lies off its middle line.
        val flat = IntArray(width * 20).also { a ->
            for (x in 5..30) listOf(40, 120, 200, 120, 40).forEachIndexed { k, v -> a[(10 + k) * width + x] = v }
        }
        val section = CursiveJoins.crossSection(flat, width, points = (10..14).map { 17.5f to it.toFloat() }, direction = 1f to 0f)
        assertEquals(listOf(200f, 160f, 120f, 80f, 40f, 20f, 0f), section.take(7))
    }

    @Test
    fun aNeighbouringStrokeIsNotPartOfTheCrossSection() {
        // The soft stroke across x 10..14 with a second one at x 17..18, 5 px from its middle line.
        val pair = IntArray(width * 10).also { a ->
            for (y in 0..9) {
                listOf(40, 120, 200, 120, 40).forEachIndexed { k, v -> a[y * width + 10 + k] = v }
                a[y * width + 17] = 150
                a[y * width + 18] = 150
            }
        }
        val section = CursiveJoins.crossSection(pair, width, points = (0..9).map { 12f to it.toFloat() }, direction = 0f to 1f)
        assertEquals(listOf(200f, 160f, 120f, 80f, 40f), section.take(5))
        assertEquals("beyond its own edge", 0f, section.drop(6).max())
    }

    @Test
    fun aCrossSectionWithAWideFlatTopIsTwoStrokesSideBySide() {
        val single = floatArrayOf(200f, 190f, 160f, 120f, 80f, 40f, 0f)
        val merged = floatArrayOf(171f, 171f, 171f, 171f, 171f, 171f, 171f, 171f, 129f, 43f, 0f)
        assertTrue(CursiveJoins.isSingleStroke(single, penPx = 6.4f))
        assertFalse(CursiveJoins.isSingleStroke(merged, penPx = 6.4f))
    }

    @Test
    fun aCrossSectionEndsAtTheGivenDistanceFadingOverItsLastPixel() {
        val soft = IntArray(width * 10).also { a ->
            for (y in 0..9) listOf(40, 120, 200, 120, 40).forEachIndexed { k, v -> a[y * width + 10 + k] = v }
        }
        val section = CursiveJoins.crossSection(soft, width, (0..9).map { 12f to it.toFloat() }, 0f to 1f, maxDistance = 1.5f)
        assertEquals(listOf(200f, 160f, 60f, 0f), section.take(4))
    }

    @Test
    fun aCutKeepsAReachOfTheLeadInPastIt() {
        // On the tail's line, but past the cut too short to show which way the stroke goes on.
        val leadIn = (0..2).map { (12 + it).toFloat() to -(12 + it).toFloat() }
        assertEquals(null, CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 1, reach = 2))
    }

    @Test
    fun theBridgeHasTheStrokesCrossSectionAndFlatEnds() {
        // Level from pixel (10, 10) to pixel (30, 10); cross-sections in half-pixel steps.
        val path = listOf(10.5f to 10.5f, 30.5f to 10.5f)
        val section = floatArrayOf(200f, 160f, 120f, 80f, 40f, 20f, 0f)
        val level = 1f to 0f
        val box = CursiveJoins.bridgeCoverage(path, level, level, section, section, left = 0, top = 0, w = 40, h = 20, extend = 1f)
        fun at(x: Int, y: Int) = box[y * 40 + x]
        assertEquals(listOf(200f, 120f, 40f, 0f), listOf(at(20, 10), at(20, 11), at(20, 12), at(20, 13)))
        assertEquals("across both sides", at(20, 9), at(20, 11))
        assertEquals("a pixel into the strokes it joins", 200f, at(9, 10))
        assertEquals("then cut off square", 0f, at(8, 10))
        assertEquals(0f, at(32, 10))
        // From one stroke's cross-section to the other's along the bridge.
        val lighter = section.map { it / 2 }.toFloatArray()
        assertEquals(150f, CursiveJoins.bridgeCoverage(path, level, level, section, lighter, 0, 0, 40, 20, extend = 1f)[10 * 40 + 20])
        // A bridge shorter than a pixel still has ends.
        val stub = listOf(10.5f to 10.5f, 10.7f to 10.5f, 10.9f to 10.5f)
        assertEquals(200f, CursiveJoins.bridgeCoverage(stub, level, level, section, section, 0, 0, 40, 20, extend = 1f)[10 * 40 + 10])
    }

    // A tail rising at 45 degrees to its end at (10, -10), as centres from the end back.
    private val tailLine = (0..10).map { (10 - it).toFloat() to -(10 - it).toFloat() }

    @Test
    fun aLeadInOnTheTailsLineJoinsWithoutCuttingEither() {
        val leadIn = (0..10).map { (12 + it).toFloat() to -(12 + it).toFloat() }
        assertEquals(CursiveJoins.Cut(0, 0), CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2))
    }

    // A tail curving up from level at its foot (-5, 0) to 45 degrees at its end (14.1, -5.9), as centres from the end back.
    private val curvedTail = (16 downTo 0).map { k -> 20f * sin(k / 16f * PI.toFloat() / 4) to -20f * (1 - cos(k / 16f * PI.toFloat() / 4)) } +
        (1..5).map { -it.toFloat() to 0f }

    @Test
    fun aLeadInThatEndsBelowTheTailsEndCutsTheTailDownToIt() {
        // At 45 degrees from (18, 0) to (23, -5), right of the tail's end and below it.
        val leadIn = (0..5).map { (18 + it).toFloat() to -it.toFloat() }
        val (i, _) = CursiveJoins.joinCuts(curvedTail, leadIn, firstLeadIn = 0, reach = 2)!!
        assertTrue("tail cut at ${curvedTail[i]}", curvedTail[i].second >= -5f)
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
    fun theBridgeTurnsOneWayIntoTheLeadInNotFirstAgainstIt() {
        // A lead-in rising at 60 degrees from (20, 0): a bridge flatter than both strokes bends twice, a wave.
        val tail = curvedTail
        val leadIn = (0..20).map { (20 + it * 0.5f) to -it * 0.866f }
        val (i, j) = CursiveJoins.joinCuts(tail, leadIn, firstLeadIn = 0, reach = 2)!!
        fun signed(a: Pair<Float, Float>, b: Pair<Float, Float>) =
            Math.toDegrees(atan2(a.first * b.second - a.second * b.first, a.first * b.first + a.second * b.second).toDouble())
        val bridge = CursiveJoins.direction(tail[i], leadIn[j])!!
        val out = signed(CursiveJoins.direction(tail[minOf(i + 2, tail.lastIndex)], tail[i])!!, bridge)
        val into = signed(bridge, CursiveJoins.direction(leadIn[j], leadIn[minOf(j + 2, leadIn.lastIndex)])!!)
        assertTrue("turns $out then $into at tail ${tail[i]}, lead-in ${leadIn[j]}", out * into >= 0 || minOf(abs(out), abs(into)) < 5)
    }

    @Test
    fun theBridgeIsChosenWithinTheLengthLimit() {
        // On the tail's line from (16, -16): the bridge from the tail's end is 8.5 px long, one cut 2 points back 11.3 px.
        val leadIn = (0..10).map { (16 + it).toFloat() to -(16 + it).toFloat() }
        assertEquals(CursiveJoins.Cut(0, 0), CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2, maxLength = 9f))
        assertEquals(null, CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2, maxLength = 8f))
    }

    @Test
    fun theNextLetterMovesWhereTheWritersStrokesMeetSoTheBridgeIsShortest() {
        // Parallel to the 45 degree tail, 14 px right of it: where it stands, only a wave or a long bridge reaches it.
        val leadIn = (0..20).map { (14 + it).toFloat() to -it.toFloat() }
        val cut = CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2, shifts = -16..16)!!
        val from = tailLine[cut.tail]
        val to = leadIn[cut.leadIn].let { (x, y) -> x + cut.shift to y }
        val length = hypot(to.first - from.first, to.second - from.second)
        assertTrue("bridge $length px at shift ${cut.shift}", length <= 3f)
    }

    @Test
    fun aLeadInCrossingTheTailsEndAtASharpAngleJoinsThroughACurveNotACorner() {
        // Rising up and to the left at 115 degrees through (10.3, -10.2), next to the tail's end: 70 degrees in under a pen width.
        val leadIn = (0..20).map { (16 - it * 0.423f) to (2 - it * 0.906f) }
        CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2)?.let { cut ->
            val length = hypot(leadIn[cut.leadIn].first - tailLine[cut.tail].first, leadIn[cut.leadIn].second - tailLine[cut.tail].second)
            assertTrue("bridge $length px", length > 4f)
        }
    }

    @Test
    fun aBridgeBackAgainstBothStrokesDoesNotJoinWhenThePenIsThinnerThanTheReach() {
        // Parallel to the tail, 4 px below its end: a 2.8 px bridge between them runs down, against both strokes.
        val leadIn = (0..20).map { (10 + it).toFloat() to (-6 - it).toFloat() }
        assertEquals(null, CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 6, maxLength = 10f, penPx = 2f))
    }

    @Test
    fun strokesUnderAPenWidthApartMeetOnlyWhereTheyOverlapAcross() {
        // Parallel to the tail, 3.5 px beside it: with a 6 px pen they touch, but stepping across reads as a notch.
        val leadIn = (0..20).map { (10 + it).toFloat() to (-5 - it).toFloat() }
        assertEquals(null, CursiveJoins.joinCuts(tailLine, leadIn, firstLeadIn = 0, reach = 2, maxLength = 10f, penPx = 6f))
    }

    @Test
    fun aStrokesDirectionFollowsAllItsCentresNotJustItsEnds() {
        // Row-run centres of a stroke rising at 20 degrees, its two end centres 1 px off the line.
        val centres = (0..5).map { k -> k * 2.75f + when (k) { 0 -> 1f; 5 -> -1f; else -> 0f } to -k.toFloat() }
        val (dx, dy) = CursiveJoins.strokeDirection(centres)!!
        assertEquals(20.0, Math.toDegrees(atan2(-dy, dx).toDouble()), 2.5)
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
    fun lettersThatEndHighDoNotJoinAndNothingJoinsIntoACapital() {
        assertTrue(CursiveJoins.joins('e', 'l'))
        assertTrue("a capital's exit stroke runs on into the word", CursiveJoins.joins('H', 'e'))
        assertFalse(CursiveJoins.joins('o', 'n'))
        assertFalse(CursiveJoins.joins('O', 'n'))
        assertFalse(CursiveJoins.joins('a', 'H'))
        assertFalse(CursiveJoins.joins('a', '.'))
    }
}
