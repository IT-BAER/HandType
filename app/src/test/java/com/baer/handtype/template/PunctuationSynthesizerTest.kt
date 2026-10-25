package com.baer.handtype.template

import org.junit.Assert.assertArrayEquals
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
}
