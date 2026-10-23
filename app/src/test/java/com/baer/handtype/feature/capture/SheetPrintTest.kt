package com.baer.handtype.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetPrintTest {

    @Test
    fun a4SheetFitsTheA4PageInsideTheMargin() {
        // A4 in points, sheet bitmap 1240x1754 (A4 at 150 dpi).
        val fit = SheetPrint.fitRect(595f, 842f, 1240, 1754, margin = 24f)

        assertTrue(fit.left >= 24f && fit.top >= 24f)
        assertTrue(fit.right <= 595f - 24f + 0.01f && fit.bottom <= 842f - 24f + 0.01f)
        assertEquals(1240f / 1754f, fit.width / fit.height, 0.001f)
    }

    @Test
    fun widthLimitedSourceIsCenteredVertically() {
        val fit = SheetPrint.fitRect(200f, 400f, 400, 200, margin = 0f)

        assertEquals(0f, fit.left, 0.001f)
        assertEquals(200f, fit.right, 0.001f)
        assertEquals(100f, fit.height, 0.001f)
        assertEquals(150f, fit.top, 0.001f)
    }

    @Test
    fun smallSourceIsScaledUpToFill() {
        val fit = SheetPrint.fitRect(100f, 100f, 10, 10, margin = 10f)

        assertEquals(80f, fit.width, 0.001f)
        assertEquals(10f, fit.left, 0.001f)
    }
}
