package com.baer.handtype.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphPostProcessorTest {

    @Test
    fun marksAboveTheBodyAreExpectedOnDottedAndAccentedLetters() {
        for (c in "ijÄÖÜäöüéè") assertTrue("$c", GlyphPostProcessor.expectsDiacritic(c))
        for (c in "IJAaßl1") assertFalse("$c", GlyphPostProcessor.expectsDiacritic(c))
    }

    @Test
    fun printedDotOnADottedGuideIsNotInk() {
        val outerGuides = intArrayOf(18, 135)
        assertTrue(GlyphPostProcessor.isOuterGuideDot(top = 17, bottom = 18, area = 4, outerGuideYsInCrop = outerGuides))
        assertTrue(GlyphPostProcessor.isOuterGuideDot(top = 134, bottom = 136, area = 6, outerGuideYsInCrop = outerGuides))
        assertFalse("pen i-dot below the cap line", GlyphPostProcessor.isOuterGuideDot(top = 21, bottom = 25, area = 20, outerGuideYsInCrop = outerGuides))
        assertFalse("accent drawn on the cap line", GlyphPostProcessor.isOuterGuideDot(top = 16, bottom = 20, area = 30, outerGuideYsInCrop = outerGuides))
    }

    @Test
    fun resolveGuideBandUsesTopAndBottomOnly() {
        assertTrue(
            GlyphPostProcessor.resolveGuideBand(intArrayOf(118, 81, 46)) == 46..118,
        )
    }

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
    fun keepsLowerArmComponentTouchingPrimaryForSplitUppercaseE() {
        assertTrue(
            GlyphPostProcessor.shouldKeepComponentBounds(
                componentLeft = 18,
                componentTop = 88,
                componentRight = 58,
                componentBottom = 96,
                componentArea = 92,
                primaryLeft = 18,
                primaryTop = 18,
                primaryRight = 32,
                primaryBottom = 86,
                primaryArea = 168,
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

class GlyphPostProcessorCloseGapsTest {

    @Test
    fun closeGapsBridgesShortBreaksInAThinStroke() {
        val width = 12
        val height = 5
        // One-pixel stroke on row 2 with a two-pixel break at x = 5..6.
        val mask = BooleanArray(width * height) { i -> i / width == 2 && (i % width) !in 5..6 }

        val closed = GlyphPostProcessor.closeGaps(mask, width, height)

        assertTrue("break bridged", closed[2 * width + 5] && closed[2 * width + 6])
        assertFalse("stroke not thickened", closed[1 * width + 3] || closed[3 * width + 3])
        assertFalse("empty rows stay empty", closed[0])
    }
}
