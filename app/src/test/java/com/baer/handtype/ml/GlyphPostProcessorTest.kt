package com.baer.handtype.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphPostProcessorTest {

    @Test
    fun keepsNearbyBodyComponentForSplitLowercaseH() {
        assertTrue(
            GlyphPostProcessor.shouldKeepComponentBounds(
                componentLeft = 44,
                componentTop = 89,
                componentRight = 61,
                componentBottom = 122,
                componentArea = 121,
                primaryLeft = 37,
                primaryTop = 71,
                primaryRight = 43,
                primaryBottom = 119,
                primaryArea = 135,
            ),
        )
    }

    @Test
    fun keepsDotComponentForLowercaseI() {
        assertTrue(
            GlyphPostProcessor.shouldKeepComponentBounds(
                componentLeft = 39,
                componentTop = 82,
                componentRight = 48,
                componentBottom = 86,
                componentArea = 22,
                primaryLeft = 42,
                primaryTop = 99,
                primaryRight = 44,
                primaryBottom = 118,
                primaryArea = 55,
            ),
        )
    }

    @Test
    fun keepsNearbyDescenderComponentForLowercaseQ() {
        assertTrue(
            GlyphPostProcessor.shouldKeepComponentBounds(
                componentLeft = 51,
                componentTop = 102,
                componentRight = 57,
                componentBottom = 139,
                componentArea = 91,
                primaryLeft = 33,
                primaryTop = 98,
                primaryRight = 49,
                primaryBottom = 124,
                primaryArea = 180,
            ),
        )
    }

    @Test
    fun rejectsWideShortGuideResidue() {
        assertFalse(
            GlyphPostProcessor.shouldKeepComponentBounds(
                componentLeft = 8,
                componentTop = 84,
                componentRight = 48,
                componentBottom = 91,
                componentArea = 88,
                primaryLeft = 37,
                primaryTop = 71,
                primaryRight = 43,
                primaryBottom = 119,
                primaryArea = 135,
            ),
        )
    }
}