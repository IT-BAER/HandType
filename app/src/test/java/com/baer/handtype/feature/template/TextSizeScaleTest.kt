package com.baer.handtype.feature.template

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSizeScaleTest {

    @Test fun defaultScaleKeepsTheBaseLineHeight() {
        assertEquals(200, TextSizeScale.lineHeightPx(basePx = 200, scale = TextSizeScale.DEFAULT))
    }

    @Test fun scaleGrowsAndShrinksTheLineHeight() {
        assertEquals(300, TextSizeScale.lineHeightPx(basePx = 200, scale = 1.5f))
        assertEquals(140, TextSizeScale.lineHeightPx(basePx = 200, scale = 0.7f))
    }

    @Test fun scaleOutsideTheSliderRangeIsClamped() {
        assertEquals(140, TextSizeScale.lineHeightPx(basePx = 200, scale = 0.1f))
        assertEquals(300, TextSizeScale.lineHeightPx(basePx = 200, scale = 4f))
    }
}
