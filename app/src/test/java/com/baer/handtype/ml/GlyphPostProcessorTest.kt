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

    @Test
    fun keepsLargeArmComponentTouchingPrimaryForSplitK() {
        // Simulate lowercase k: thin stem = primary, diagonal arm touches stem, arm area ~ same size.
        assertTrue(
            GlyphPostProcessor.shouldKeepComponentBounds(
                componentLeft = 15,
                componentTop = 40,
                componentRight = 55,
                componentBottom = 75,
                componentArea = 130,  // close to primaryArea — would fail old 0.95× limit
                primaryLeft = 10,
                primaryTop = 10,
                primaryRight = 15,
                primaryBottom = 100,
                primaryArea = 135,
            ),
        )
    }

    @Test
    fun keepsLargeBodyAbovePrimaryForSplitE() {
        // Simulate 'E': bottom arm is primary, stem+top-arms are above and larger than primaryArea/2.
        assertTrue(
            GlyphPostProcessor.shouldKeepComponentBounds(
                componentLeft = 20,
                componentTop = 10,
                componentRight = 35,
                componentBottom = 70,
                componentArea = 110,  // > primaryArea/2 = 55 — would fail old limit
                primaryLeft = 20,
                primaryTop = 75,
                primaryRight = 60,
                primaryBottom = 88,
                primaryArea = 110,
            ),
        )
    }

    @Test
    fun keepsLargeBowlAbovePrimaryForSplitG() {
        // Simulate 'g': descender loop = primary, bowl above, bowl area > primaryArea/2.
        assertTrue(
            GlyphPostProcessor.shouldKeepComponentBounds(
                componentLeft = 18,
                componentTop = 20,
                componentRight = 50,
                componentBottom = 65,
                componentArea = 160,  // > primaryArea/2 = 70 — would fail old limit
                primaryLeft = 22,
                primaryTop = 68,
                primaryRight = 48,
                primaryBottom = 100,
                primaryArea = 140,
            ),
        )
    }
}