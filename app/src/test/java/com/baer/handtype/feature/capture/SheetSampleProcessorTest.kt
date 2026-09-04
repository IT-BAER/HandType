package com.baer.handtype.feature.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetSampleProcessorTest {

    @Test
    fun paperMaskCoversInkEnclosedByThePage() {
        val width = 20
        val height = 20
        // Bright page 4..15 with a dark 3x3 fiducial at 6..8 and dark table outside.
        val bright = BooleanArray(width * height) { i ->
            val x = i % width
            val y = i / width
            x in 4..15 && y in 4..15 && !(x in 6..8 && y in 6..8)
        }

        val filled = SheetSampleProcessor.fillEnclosedHoles(bright, width, height)

        assertTrue("fiducial pixel belongs to the page", filled[7 * width + 7])
        assertTrue("page pixel kept", filled[10 * width + 10])
        assertFalse("table outside the page stays excluded", filled[2 * width + 2])
        assertFalse("table touching the frame edge stays excluded", filled[0])
    }

    @Test
    fun brightCutoffKeepsShadowedPaperAndRejectsWood() {
        // Wood peak at 119, paper peak at 180, Otsu between them at 139 (measured on a phone photo).
        val histogram = IntArray(256).also { it[119] = 40_000; it[180] = 60_000; it[165] = 5_000 }

        val (cutoff, peak) = SheetSampleProcessor.pageBrightCutoff(histogram, otsu = 139)

        assertTrue("paper peak found", peak == 180)
        assertTrue("shadowed paper at 165 counts as page (cutoff $cutoff)", cutoff <= 165)
        assertTrue("wood at 119 stays out (cutoff $cutoff)", cutoff > 119)
    }
}
