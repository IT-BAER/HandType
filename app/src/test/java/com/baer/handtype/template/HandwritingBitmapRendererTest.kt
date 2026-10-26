package com.baer.handtype.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingBitmapRendererTest {

    // A descender on a 20 x 60 glyph, baseline row 30: a stem (columns 8..9) from row 10 down to row 40 and a
    // foot (rows 39..40, columns 4..9) turning left, 10 rows deep.
    private val ink = 0xFF202020.toInt()
    private val descender = IntArray(20 * 60) { i ->
        val x = i % 20
        val y = i / 20
        if ((x in 8..9 && y in 10..40) || (x in 4..9 && y in 39..40)) ink else 0
    }

    private fun inkRows(pixels: IntArray) = (0 until 60).filter { y -> (0 until 20).any { x -> (pixels[y * 20 + x] ushr 24) > 128 } }

    @Test
    fun aDescenderLengthensInItsMiddleKeepingItsFootAndEverythingAboveIt() {
        val longer = HandwritingBitmapRenderer.lengthenDescender(descender, 20, baselineRow = 30, inkBottom = 40, factor = 1.6f, lean = 0f)
        assertEquals("16 rows deep", 46, inkRows(longer).max())
        assertEquals("above the stretch", (0 until 20 * 33).map { descender[it] }, (0 until 20 * 33).map { longer[it] })
        val foot = (0 until 60).filter { y -> (4..6).any { x -> (longer[y * 20 + x] ushr 24) > 128 } }
        assertEquals("the foot keeps its two rows", listOf(45, 46), foot)
    }

    @Test
    fun aLengthenedDescenderKeepsTheHandsSlant() {
        // The hand leans 0.5 px right per row going up: moved 6 rows down, the foot moves 3 px left.
        val longer = HandwritingBitmapRenderer.lengthenDescender(descender, 20, baselineRow = 30, inkBottom = 40, factor = 1.6f, lean = 0.5f)
        val footColumns = (0 until 20).filter { x -> (longer[46 * 20 + x] ushr 24) > 128 }
        assertEquals((1..6).toList(), footColumns)
    }

    @Test
    fun aHandsDescendersLengthenToSixTenthsOfItsXHeight() {
        // Written to the sheet's dotted line, a third of the x-height deep.
        assertEquals(1.8f, HandwritingBitmapRenderer.descenderFactor(listOf(33, 35, 30), xHeightPx = 100), 0.001f)
        assertEquals(1.2f, HandwritingBitmapRenderer.descenderFactor(listOf(50, 52, 48), xHeightPx = 100), 0.001f)
        assertEquals("deep enough", 1f, HandwritingBitmapRenderer.descenderFactor(listOf(70, 90), xHeightPx = 100), 0.001f)
        assertEquals("unknown", 1f, HandwritingBitmapRenderer.descenderFactor(emptyList(), xHeightPx = 100), 0.001f)
    }

    @Test
    fun inkRowBandsTileTheNoteHeightAndSplitBetweenLines() {
        val bands = HandwritingBitmapRenderer.inkRowBands(
            rows = listOf(InkRow(10, 20, 100, 60), InkRow(12, 80, 90, 120)),
            height = 150,
        )
        assertEquals(
            listOf(InkRow(10, 0, 100, 70, inkTop = 20, inkBottom = 60), InkRow(12, 70, 90, 150, inkTop = 80, inkBottom = 120)),
            bands,
        )
    }

    @Test
    fun inkRowBandsSplitOverlappingLinesInTheMiddleOfTheOverlap() {
        // A descender of line 1 reaches below the ascenders of line 2.
        val bands = HandwritingBitmapRenderer.inkRowBands(
            rows = listOf(InkRow(10, 20, 100, 90), InkRow(12, 80, 90, 140), InkRow(8, 130, 70, 170)),
            height = 180,
        )
        assertEquals(
            listOf(
                InkRow(10, 0, 100, 85, inkTop = 20, inkBottom = 90),
                InkRow(12, 85, 90, 135, inkTop = 80, inkBottom = 140),
                InkRow(8, 135, 70, 180, inkTop = 130, inkBottom = 170),
            ),
            bands,
        )
    }

    @Test
    fun inkRowBandsOfASingleLineCoverTheWholeHeight() {
        assertEquals(
            listOf(InkRow(5, 0, 50, 99, inkTop = 30, inkBottom = 60)),
            HandwritingBitmapRenderer.inkRowBands(listOf(InkRow(5, 30, 50, 60)), height = 99),
        )
        assertEquals(emptyList<InkRow>(), HandwritingBitmapRenderer.inkRowBands(emptyList(), height = 99))
    }

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
    fun capitalsOfDescenderLettersRestOnTheirInkBottom() {
        for (c in "GJPQY") assertEquals("$c", 145, HandwritingBitmapRenderer.userCanvasBaselineRow(c, canvasBaseline = 163, inkBottom = 145))
    }

    @Test
    fun descendersHangFromTheWritersBaselineNotThePrintedOne() {
        // A small hand writes above the printed baseline; its g must not float above its a.
        val writerBaseline = HandwritingBitmapRenderer.writerBaselineRow(canvasBaseline = 163, xHeightInkBottoms = listOf(141, 145, 143))
        assertEquals(143, writerBaseline)
        assertEquals(143, HandwritingBitmapRenderer.userCanvasBaselineRow('g', canvasBaseline = 163, inkBottom = 165, writerBaseline = writerBaseline))
        assertEquals("synthesized marks stay on the canvas", 163, HandwritingBitmapRenderer.userCanvasBaselineRow('.', canvasBaseline = 163, inkBottom = 163, writerBaseline = writerBaseline))
        assertEquals(163, HandwritingBitmapRenderer.writerBaselineRow(canvasBaseline = 163, xHeightInkBottoms = emptyList()))
    }

    @Test
    fun aCursiveLeadInSlidesUnderThePreviousExitStroke() {
        // Previous letter: exit stroke high on the right (rows 0-4 reach col 9), body low (col 5).
        val prevRight = IntArray(10) { if (it < 5) 9 else 5 }
        // Next letter: lead-in starts low on the left (col 0), body high (col 5).
        val nextLeft = IntArray(10) { if (it < 5) 5 else 0 }
        // Rows 5-9 need 6 px (right edge 6 vs left 0), rows 0-4 need 5 px; plus a 1 px gap.
        assertEquals(5, kern(prevRight, nextLeft, distancePx = 12, gapPx = 1, maxKernPx = 20))
        assertEquals("never more than the bound", 3, kern(prevRight, nextLeft, distancePx = 12, gapPx = 1, maxKernPx = 3))
    }

    @Test
    fun roundPrintLettersKeepTheirSpacing() {
        val o = IntArray(10) { 9 }
        val oLeft = IntArray(10) { 0 }
        assertEquals(0, kern(o, oLeft, distancePx = 12, gapPx = 3, maxKernPx = 20))
    }

    @Test
    fun lettersWithoutSharedRowsDoNotMove() {
        val high = IntArray(10) { if (it < 3) 4 else -1 }
        val low = IntArray(10) { if (it > 6) 0 else -1 }
        assertEquals(0, kern(high, low, distancePx = 12, gapPx = 1, maxKernPx = 20))
    }

    private fun kern(prevRight: IntArray, nextLeft: IntArray, distancePx: Int, gapPx: Int, maxKernPx: Int) =
        HandwritingBitmapRenderer.kerningPx(
            prevRight, prevBaselineRow = 9, prevScaleX = 1f, prevScaleY = 1f,
            nextLeft, nextBaselineRow = 9, nextScaleX = 1f, nextScaleY = 1f,
            distancePx = distancePx, gapPx = gapPx, maxKernPx = maxKernPx,
        )

    @Test
    fun aHandThatFillsTheGuideBandIsScaledToTheRuledLine() {
        val reference = HandwritingBitmapRenderer.writerSizeScale(listOf(64, 60, 68))
        assertEquals(1f, reference, 0.001f)
        // The sheet asks small letters to reach the upper dashed line: x-height 101 on the canvas.
        assertTrue(HandwritingBitmapRenderer.writerSizeScale(listOf(101, 98, 104)) < 0.7f)
        assertEquals("a tiny hand grows, but stays small", 1.6f, HandwritingBitmapRenderer.writerSizeScale(listOf(25, 24, 26)), 0.001f)
        assertEquals(1f, HandwritingBitmapRenderer.writerSizeScale(emptyList()), 0.001f)
    }

    @Test
    fun cursiveLetterReachingTheDescenderGuideKeepsTheCanvasBaseline() {
        assertEquals(163, HandwritingBitmapRenderer.userCanvasBaselineRow('ß', canvasBaseline = 163, inkBottom = 197))
        assertEquals(163, HandwritingBitmapRenderer.userCanvasBaselineRow('f', canvasBaseline = 163, inkBottom = 197))
        assertEquals("print ß dips just below the line", 170, HandwritingBitmapRenderer.userCanvasBaselineRow('ß', canvasBaseline = 163, inkBottom = 170))
    }

    @Test
    fun synthesizedPunctuationKeepsTheCanvasBaseline() {
        // A hyphen's ink bottom sits mid x-height; treating it as the baseline drops it to the line.
        assertEquals(163, HandwritingBitmapRenderer.userCanvasBaselineRow('-', canvasBaseline = 163, inkBottom = 115))
        assertEquals(163, HandwritingBitmapRenderer.userCanvasBaselineRow('\'', canvasBaseline = 163, inkBottom = 90))
        assertEquals(163, HandwritingBitmapRenderer.userCanvasBaselineRow(',', canvasBaseline = 163, inkBottom = 177))
    }
}
