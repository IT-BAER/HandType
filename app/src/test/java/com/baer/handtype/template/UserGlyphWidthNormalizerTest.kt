package com.baer.handtype.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserGlyphWidthNormalizerTest {

    @Test
    fun leavesBundledTemplateGlyphsUntouched() {
        val (scaleX, scaleY) = UserGlyphWidthNormalizer.scales(
            character = 'n',
            templateId = "classic_script",
            glyphWidthPx = 157,
            glyphHeightPx = 141,
            targetInkHeightPx = 120,
        )

        assertEquals(scaleY, scaleX, 0.0001f)
    }

    @Test
    fun narrowsOverwideUserTemplateLowercaseGlyphs() {
        val (scaleX, scaleY) = UserGlyphWidthNormalizer.scales(
            character = 'u',
            templateId = "user_123",
            glyphWidthPx = 162,
            glyphHeightPx = 149,
            targetInkHeightPx = 120,
        )

        assertTrue(scaleX < scaleY)
    }

    @Test
    fun aLigatureMayBeAsWideAsTwoRoundLetters() {
        // o and e at 0.7 of the height each, side by side: 1.4.
        val (scaleX, scaleY) = UserGlyphWidthNormalizer.scales(
            character = 'œ',
            templateId = "user_123",
            glyphWidthPx = 140,
            glyphHeightPx = 100,
            targetInkHeightPx = 120,
        )

        assertEquals(scaleY, scaleX, 0.0001f)
    }

    @Test
    fun keepsReasonableUserTemplateGlyphsAsIs() {
        val (scaleX, scaleY) = UserGlyphWidthNormalizer.scales(
            character = 'a',
            templateId = "user_123",
            glyphWidthPx = 98,
            glyphHeightPx = 160,
            targetInkHeightPx = 120,
        )

        assertEquals(scaleY, scaleX, 0.0001f)
    }
}