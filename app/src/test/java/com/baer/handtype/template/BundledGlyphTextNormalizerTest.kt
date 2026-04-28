package com.baer.handtype.template

import org.junit.Assert.assertEquals
import org.junit.Test

class BundledGlyphTextNormalizerTest {

    @Test
    fun normalizesSmartPunctuationToBundledAsciiGlyphs() {
        val input = "Hello\u2014world\u2026 \u2018yes\u2019 \u201Cno\u201D"

        assertEquals("Hello-world... 'yes' ''no''", BundledGlyphTextNormalizer.normalize(input))
    }

    @Test
    fun normalizesLocalizedCommaPeriodAndQuestionMarks() {
        val input = "A\u3001B\u3002 C\u060CD\u061F E\uFF0CF\uFF0E G\uFF1F"

        assertEquals("A,B. C,D? E,F. G?", BundledGlyphTextNormalizer.normalize(input))
    }

    @Test
    fun normalizesWhitespaceButKeepsLineBreaks() {
        val input = "one\u00A0two\tthree\r\nfour\rfive"

        assertEquals("one two three\nfour\nfive", BundledGlyphTextNormalizer.normalize(input))
    }
}