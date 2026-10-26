package com.baer.handtype.template

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationSynthesizerTest {

    @Test
    fun theDotOfAnIIsTheLargestPieceAboveItsBody() {
        val width = 20
        // Body at columns 8..10, rows 20..39; dot at 7..11, rows 5..8; a speck at row 2.
        val alpha = IntArray(width * 40) { i ->
            val x = i % width
            val y = i / width
            when {
                x in 8..10 && y in 20..39 -> 255
                x in 7..11 && y in 5..8 -> 230
                x == 15 && y == 2 -> 255
                else -> 0
            }
        }

        assertArrayEquals(intArrayOf(7, 5, 11, 8), PunctuationSynthesizer.markBounds(alpha, width))
    }

    @Test
    fun aLetterWithoutADotHasNoMark() {
        val width = 20
        val alpha = IntArray(width * 40) { i -> if (i % width in 8..10 && i / width in 5..39) 255 else 0 }

        assertNull(PunctuationSynthesizer.markBounds(alpha, width))
    }

    @Test
    fun aStraightStrokeIsAStemAndALoopOrCurlIsNot() {
        // Ink extent of each row, top to bottom, of a 100-row glyph with a 10 px pen.
        assertTrue("slanted print l", PunctuationSynthesizer.isStemLike(IntArray(100) { 10 }))
        assertTrue("pen-down blob", PunctuationSynthesizer.isStemLike(IntArray(100) { if (it < 2) 22 else 10 }))
        assertFalse("cursive l loop", PunctuationSynthesizer.isStemLike(IntArray(100) { if (it in 10..50) 34 else 10 }))
        assertFalse("exit curl", PunctuationSynthesizer.isStemLike(IntArray(100) { if (it >= 90) 30 else 10 }))
        assertFalse("too short to stretch", PunctuationSynthesizer.isStemLike(IntArray(20) { 4 }))
    }

    @Test
    fun theLeanOfAStemIsTakenFromItsSingleStrokeRows() {
        val width = 60
        // A 5 px stem leaning 0.25 px per row going up over rows 0..99; rows 0..29 also carry a loop
        // stroke 12 px to the right, which would pull a whole-row centre off the stem.
        val alpha = IntArray(width * 100) { i ->
            val x = i % width
            val y = i / width
            val stem = 10 + (99 - y) / 4
            when {
                x in stem..stem + 4 -> 255
                y < 30 && x in stem + 16..stem + 20 -> 255
                else -> 0
            }
        }

        assertEquals(0.25f, PunctuationSynthesizer.stemLean(alpha, width, 0..99)!!, 0.01f)
        assertNull("too few rows", PunctuationSynthesizer.stemLean(alpha, width, 0..5))
    }

    @Test
    fun aPenStrokeHasRoundEndsAndNeverStacksOnInkUnderIt() {
        val width = 40
        val px = IntArray(width * 20)
        px[10 * width + 15] = 230 shl 24
        px[10 * width + 25] = 160 shl 24
        // A 4 px pen of alpha 200 along the centres of row 10, x 10 to 30.
        PunctuationSynthesizer.drawStroke(px, width, listOf(10f to 10.5f, 30f to 10.5f), PunctuationSynthesizer.penSection(4f, 200), 0x112233)
        fun alphaAt(x: Int, y: Int) = px[y * width + x] ushr 24

        assertEquals("on the line", 200, alphaAt(20, 10))
        assertEquals("at the pen's edge", 200, alphaAt(20, 12))
        assertEquals("past the pen", 0, alphaAt(20, 13))
        assertEquals("round end, fading", 100, alphaAt(32, 10))
        assertEquals("past the round end", 0, alphaAt(33, 10))
        assertEquals("darker ink stays", 230, alphaAt(15, 10))
        assertEquals("lighter ink takes the pen's alpha", 200, alphaAt(25, 10))
    }
}
