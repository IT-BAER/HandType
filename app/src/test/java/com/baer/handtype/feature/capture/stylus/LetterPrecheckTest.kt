package com.baer.handtype.feature.capture.stylus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterPrecheckTest {

    private fun stroke(vararg xy: Float): InkStroke =
        InkStroke(xy.toList().chunked(2).mapIndexed { i, (x, y) -> InkPoint(x, y, i * 10L) })

    // Vertical bar from the cell top area down to the baseline (0.78): a tall character.
    private val tallStroke = stroke(0.5f, 0.15f, 0.5f, 0.45f, 0.5f, 0.78f)

    // Loop between the x-height guide (0.32) and the baseline (0.78): a small lowercase letter.
    private val xHeightStroke = stroke(0.6f, 0.34f, 0.4f, 0.34f, 0.35f, 0.55f, 0.4f, 0.77f, 0.6f, 0.77f, 0.65f, 0.55f)

    @Test
    fun emptyInkFails() {
        assertEquals(PrecheckResult.Empty, LetterPrecheck.evaluate('a', emptyList(), null))
        assertEquals(PrecheckResult.Empty, LetterPrecheck.evaluate('a', listOf(InkStroke(emptyList())), null))
    }

    @Test
    fun pointOutsideTheCellFails() {
        val outside = stroke(0.5f, 0.15f, 0.5f, 0.78f, 1.05f, 0.78f)
        assertEquals(PrecheckResult.OutsideBox, LetterPrecheck.evaluate('A', listOf(outside), null))
    }

    @Test
    fun inkFarBelowTheGuideGapIsTooSmall() {
        val tiny = stroke(0.5f, 0.70f, 0.52f, 0.78f)
        assertEquals(PrecheckResult.TooSmall, LetterPrecheck.evaluate('a', listOf(tiny), null))
    }

    @Test
    fun inkSpanningNearlyTheWholeCellIsTooLarge() {
        val huge = stroke(0.5f, 0.01f, 0.5f, 0.99f)
        assertEquals(PrecheckResult.TooLarge, LetterPrecheck.evaluate('l', listOf(huge), null))
    }

    @Test
    fun capitalMustReachAboveTheUpperGuide() {
        assertEquals(PrecheckResult.ReachUp, LetterPrecheck.evaluate('A', listOf(xHeightStroke), null))
        assertEquals(PrecheckResult.ReachUp, LetterPrecheck.evaluate('7', listOf(xHeightStroke), null))
        assertEquals(PrecheckResult.ReachUp, LetterPrecheck.evaluate('h', listOf(xHeightStroke), null))
        assertEquals(PrecheckResult.Pass, LetterPrecheck.evaluate('a', listOf(xHeightStroke), null))
        assertEquals(PrecheckResult.Pass, LetterPrecheck.evaluate('A', listOf(tallStroke), null))
    }

    @Test
    fun shapeOnlyPassesWhenRecognitionIsUnavailable() {
        assertEquals(PrecheckResult.Pass, LetterPrecheck.evaluate('B', listOf(tallStroke), null))
    }

    @Test
    fun wrongLetterReportsTheTopCandidate() {
        val result = LetterPrecheck.evaluate('a', listOf(xHeightStroke), listOf("o", "e", "u"))
        assertEquals(PrecheckResult.WrongLetter("o"), result)
        assertEquals(PrecheckResult.WrongLetter(null), LetterPrecheck.evaluate('a', listOf(xHeightStroke), emptyList()))
    }

    @Test
    fun expectedCharacterAnywhereInTheTopFivePasses() {
        val candidates = listOf("o", "e", "u", "n", "a", "x")
        assertEquals(PrecheckResult.Pass, LetterPrecheck.evaluate('a', listOf(xHeightStroke), candidates))
        val sixth = listOf("o", "e", "u", "n", "x", "a")
        assertEquals(PrecheckResult.WrongLetter("o"), LetterPrecheck.evaluate('a', listOf(xHeightStroke), sixth))
    }

    @Test
    fun candidatesAreTrimmed() {
        assertTrue(LetterPrecheck.matches('a', " a "))
    }

    @Test
    fun shapeIdenticalPairsMatchIgnoringCase() {
        for (c in "cosuvwxz") {
            assertTrue("$c", LetterPrecheck.matches(c, c.uppercaseChar().toString()))
            assertTrue("$c", LetterPrecheck.matches(c.uppercaseChar(), c.toString()))
        }
        assertTrue(LetterPrecheck.matches('0', "O"))
        assertTrue(LetterPrecheck.matches('O', "0"))
        assertTrue(LetterPrecheck.matches('1', "l"))
        assertTrue(LetterPrecheck.matches('l', "I"))
        assertTrue(LetterPrecheck.matches('I', "1"))
    }

    @Test
    fun otherLettersAreCaseSensitive() {
        assertFalse(LetterPrecheck.matches('a', "A"))
        assertFalse(LetterPrecheck.matches('B', "b"))
        assertFalse(LetterPrecheck.matches('o', "0"))
        assertFalse(LetterPrecheck.matches('a', "ab"))
    }
}
