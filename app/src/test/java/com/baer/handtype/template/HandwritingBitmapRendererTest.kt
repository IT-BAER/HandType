package com.baer.handtype.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingBitmapRendererTest {

    @Test
    fun bundledUmlautsKeepTheBodySizeOfTheirBaseLetter() {
        // The dots add ink height, so the umlaut needs a larger target for the same body.
        for ((umlaut, base) in listOf('Ä' to 'A', 'Ö' to 'O', 'Ü' to 'U', 'ä' to 'a', 'ö' to 'o', 'ü' to 'u')) {
            val umlautRatio = HandwritingBitmapRenderer.targetInkHeightRatio(umlaut, isUserTemplate = false)
            val baseRatio = HandwritingBitmapRenderer.targetInkHeightRatio(base, isUserTemplate = false)
            assertTrue("$umlaut $umlautRatio vs $base $baseRatio", umlautRatio > baseRatio * 1.15f)
        }
        assertEquals(
            HandwritingBitmapRenderer.targetInkHeightRatio('b', isUserTemplate = false),
            HandwritingBitmapRenderer.targetInkHeightRatio('ß', isUserTemplate = false),
            0f,
        )
    }

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

class BundledPunctuationTest {
    private fun bundledRatio(c: Char) = HandwritingBitmapRenderer.targetInkHeightRatio(c, isUserTemplate = false)

    @Test
    fun bundledMarksKeepTheAssetProportionsOfTheLetters() {
        // Bundled ink heights against 'l' (100 px): ! 98, ? 90, , 33, ' 35, . 19, - 12.
        assertEquals(0.64f, bundledRatio('!'), 0.0001f)
        assertEquals(0.59f, bundledRatio('?'), 0.0001f)
        assertEquals(0.22f, bundledRatio(','), 0.0001f)
        assertEquals(0.23f, bundledRatio('\''), 0.0001f)
        assertEquals(0.13f, bundledRatio('.'), 0.0001f)
        assertEquals(0.08f, bundledRatio('-'), 0.0001f)
    }

    @Test
    fun userTemplatePunctuationRatioIsUnchanged() {
        assertEquals(0.28f, HandwritingBitmapRenderer.targetInkHeightRatio('!', isUserTemplate = true), 0.0001f)
    }

    private fun row(c: Char, top: Int, bottom: Int) =
        HandwritingBitmapRenderer.bundledBaselineRow(c, inkTop = top, inkBottom = bottom, templateBaselineRow = 138, isUserTemplate = false)

    @Test
    fun bundledLettersRestOnTheirInkBottom() {
        assertEquals(78, row('a', top = 60, bottom = 138))
        assertEquals(98, row('!', top = 40, bottom = 138))
    }

    @Test
    fun bundledRaisedMarksSitAboveTheTemplateBaseline() {
        // Row past the ink bottom lifts the mark: apostrophe near cap height, hyphen at mid x-height.
        assertEquals(109, row('\'', top = 29, bottom = 64))
        assertEquals(46, row('-', top = 92, bottom = 104))
    }

    @Test
    fun bundledCommaHangsBelowTheTemplateBaseline() {
        assertEquals(19, row(',', top = 119, bottom = 152))
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
