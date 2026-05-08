package com.baer.handtype.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingBitmapRendererTest {

    @Test
    fun userTemplateTallAndDescenderClassesStayLargerThanXHeight() {
        val regular = HandwritingBitmapRenderer.targetInkHeightRatio('a', isUserTemplate = true)
        val tall = HandwritingBitmapRenderer.targetInkHeightRatio('h', isUserTemplate = true)
        val descender = HandwritingBitmapRenderer.targetInkHeightRatio('y', isUserTemplate = true)
        val uppercase = HandwritingBitmapRenderer.targetInkHeightRatio('E', isUserTemplate = true)

        assertTrue(tall > regular)
        assertTrue(descender > regular)
        assertTrue(uppercase > tall)
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
}