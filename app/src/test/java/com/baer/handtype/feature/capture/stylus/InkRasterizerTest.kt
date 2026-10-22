package com.baer.handtype.feature.capture.stylus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InkRasterizerTest {

    // The printed cell measures 114 x 171 px on the rectified 1240 x 1754 sheet.
    private val geometry = CellGeometry(cellWidthPx = 114, cellHeightPx = 171)

    private fun luma(argb: Int): Int = argb and 0xFF

    private fun stroke(vararg xy: Float): InkStroke =
        InkStroke(xy.toList().chunked(2).mapIndexed { i, (x, y) -> InkPoint(x, y, i * 10L) })

    @Test
    fun geometryMatchesTheScanCrop() {
        assertEquals(5, geometry.topInset)
        assertEquals(3, geometry.sideInset)
        assertEquals(108, geometry.cropWidth)
        assertEquals(163, geometry.cropHeight)
        assertArrayEquals(intArrayOf(49, 128), geometry.guideYsInCrop)
    }

    @Test
    fun noInkGivesWhitePaper() {
        val pixels = InkRasterizer.rasterize(emptyList(), geometry)
        assertEquals(geometry.cropWidth * geometry.cropHeight, pixels.size)
        assertTrue(pixels.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun baselineStrokeLandsOnTheBaselineGuideRow() {
        val pixels = InkRasterizer.rasterize(listOf(stroke(0.2f, 0.78f, 0.8f, 0.78f)), geometry)
        val w = geometry.cropWidth
        val darkRows = (0 until geometry.cropHeight).filter { y ->
            (0 until w).any { x -> luma(pixels[y * w + x]) < 128 }
        }
        assertTrue("dark rows $darkRows", darkRows.isNotEmpty())
        val baseline = geometry.guideYsInCrop[1]
        assertTrue("dark rows $darkRows vs baseline $baseline", darkRows.all { abs(it - baseline) <= 2 })
        val darkInRow = (0 until w).count { x -> luma(pixels[baseline * w + x]) < 128 }
        assertTrue("dark pixels in row: $darkInRow", darkInRow in 55..75)
    }

    @Test
    fun dotMapsToItsCellPosition() {
        val pixels = InkRasterizer.rasterize(listOf(stroke(0.5f, 0.5f)), geometry)
        val w = geometry.cropWidth
        val darkest = pixels.indices.minByOrNull { luma(pixels[it]) }!!
        val expectedX = 0.5f * 114 - geometry.sideInset
        val expectedY = 0.5f * 171 - geometry.topInset
        assertTrue(abs(darkest % w + 0.5f - expectedX) <= 1.5f)
        assertTrue(abs(darkest / w + 0.5f - expectedY) <= 1.5f)
        assertTrue(luma(pixels[darkest]) < 64)
    }

    @Test
    fun inkOutsideTheCellIsClipped() {
        val pixels = InkRasterizer.rasterize(listOf(stroke(-0.5f, 0.5f, 1.5f, 0.5f)), geometry)
        val w = geometry.cropWidth
        val row = (0.5f * 171 - geometry.topInset).toInt()
        assertTrue((0 until w).all { x -> luma(pixels[row * w + x]) < 128 })
    }
}
