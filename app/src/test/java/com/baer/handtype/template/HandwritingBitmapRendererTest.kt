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
