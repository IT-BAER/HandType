package com.baer.handtype.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingBitmapRendererTest {

    @Test
    fun normalizedUserCanvasUsesOneSharedTargetHeight() {
        val lineHeightPx = 140

        val regular = HandwritingBitmapRenderer.targetInkHeightPx(
            lineHeightPx = lineHeightPx,
            character = 'a',
            isUserTemplate = true,
            normalizedUserCanvas = true,
        )
        val tall = HandwritingBitmapRenderer.targetInkHeightPx(
            lineHeightPx = lineHeightPx,
            character = 'h',
            isUserTemplate = true,
            normalizedUserCanvas = true,
        )
        val descender = HandwritingBitmapRenderer.targetInkHeightPx(
            lineHeightPx = lineHeightPx,
            character = 'y',
            isUserTemplate = true,
            normalizedUserCanvas = true,
        )
        val uppercase = HandwritingBitmapRenderer.targetInkHeightPx(
            lineHeightPx = lineHeightPx,
            character = 'E',
            isUserTemplate = true,
            normalizedUserCanvas = true,
        )

        assertEquals(lineHeightPx, regular)
        assertEquals(regular, tall)
        assertEquals(regular, descender)
        assertEquals(regular, uppercase)
    }

    @Test
    fun userTemplateDescendersDropFurtherThanBundled() {
        assertTrue(
            HandwritingBitmapRenderer.descenderDropRatio(isUserTemplate = true) >
                HandwritingBitmapRenderer.descenderDropRatio(isUserTemplate = false),
        )
    }

    @Test
    fun bundledTemplateRatiosStayUnchangedForBaseClasses() {
        assertEquals(0.80f, HandwritingBitmapRenderer.targetInkHeightRatio('E', isUserTemplate = false), 0.0001f)
        assertEquals(0.66f, HandwritingBitmapRenderer.targetInkHeightRatio('h', isUserTemplate = false), 0.0001f)
        assertEquals(0.64f, HandwritingBitmapRenderer.targetInkHeightRatio('y', isUserTemplate = false), 0.0001f)
    }

    @Test
    fun legacyUserTemplateRatiosStillDifferentByClass() {
        val regular = HandwritingBitmapRenderer.targetInkHeightPx(
            lineHeightPx = 140,
            character = 'a',
            isUserTemplate = true,
            normalizedUserCanvas = false,
        )
        val tall = HandwritingBitmapRenderer.targetInkHeightPx(
            lineHeightPx = 140,
            character = 'h',
            isUserTemplate = true,
            normalizedUserCanvas = false,
        )
        assertTrue(tall > regular)
    }
}

class LeadingPullTest {
    @Test
    fun narrowLettersPullOnlyUnderATuckedNeighbour() {
        val afterTucked = HandwritingBitmapRenderer.leadingPullPx('l', 200, true, previousTucked = true)
        val afterRound = HandwritingBitmapRenderer.leadingPullPx('l', 200, true, previousTucked = false)
        assertTrue(afterTucked > 0)
        assertEquals(0, afterRound)
    }

    @Test
    fun xHeightTopIsTheMedianOfLowercaseTops() {
        assertEquals(120, HandwritingBitmapRenderer.xHeightTopRow(listOf(130, 110, 120)))
        assertEquals(PunctuationSynthesizer.InkStyle.DEFAULT.xHeightTop, HandwritingBitmapRenderer.xHeightTopRow(emptyList()))
    }

    @Test
    fun userCanvasLettersRestOnTheirInkBottom() {
        assertEquals(150, HandwritingBitmapRenderer.userCanvasBaselineRow('a', canvasBaseline = 163, inkBottom = 150))
        assertEquals(163, HandwritingBitmapRenderer.userCanvasBaselineRow('g', canvasBaseline = 163, inkBottom = 200))
    }

    @Test
    fun synthesizedPunctuationKeepsTheCanvasBaseline() {
        // A hyphen's ink bottom sits mid x-height; treating it as the baseline drops it to the line.
        assertEquals(163, HandwritingBitmapRenderer.userCanvasBaselineRow('-', canvasBaseline = 163, inkBottom = 115))
        assertEquals(163, HandwritingBitmapRenderer.userCanvasBaselineRow('\'', canvasBaseline = 163, inkBottom = 90))
        assertEquals(163, HandwritingBitmapRenderer.userCanvasBaselineRow(',', canvasBaseline = 163, inkBottom = 177))
    }
}
