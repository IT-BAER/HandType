package com.baer.handtype.feature.capture

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PaperCornerDetectorTest {

    @Test
    fun detectsStronglyRotatedPortraitSheet() {
        val width = 260
        val height = 320
        val expected = rotatedRect(
            centerX = 130.0,
            centerY = 160.0,
            rectWidth = 120.0,
            rectHeight = 220.0,
            angleDeg = 32.0,
        )

        val detected = PaperCornerDetector.detect(maskForQuad(width, height, expected), width, height)

        assertNotNull(detected)
        assertCornerNear(expected[0], detected!!.topLeft)
        assertCornerNear(expected[1], detected.topRight)
        assertCornerNear(expected[2], detected.bottomRight)
        assertCornerNear(expected[3], detected.bottomLeft)
    }

    @Test
    fun detectsPerspectiveLikeTrapezoid() {
        val width = 280
        val height = 340
        val expected = listOf(
            PaperCornerDetector.Point(72f, 34f),
            PaperCornerDetector.Point(214f, 62f),
            PaperCornerDetector.Point(182f, 304f),
            PaperCornerDetector.Point(38f, 272f),
        )

        val detected = PaperCornerDetector.detect(maskForQuad(width, height, expected), width, height)

        assertNotNull(detected)
        assertCornerNear(expected[0], detected!!.topLeft, tolerance = 16.0)
        assertCornerNear(expected[1], detected.topRight, tolerance = 16.0)
        assertCornerNear(expected[2], detected.bottomRight, tolerance = 16.0)
        assertCornerNear(expected[3], detected.bottomLeft, tolerance = 16.0)
    }

    private fun assertCornerNear(
        expected: PaperCornerDetector.Point,
        actual: PaperCornerDetector.Point,
        tolerance: Double = 10.0,
    ) {
        val distance = hypot(
            (expected.x - actual.x).toDouble(),
            (expected.y - actual.y).toDouble(),
        )
        assertTrue("corner distance $distance > $tolerance", distance <= tolerance)
    }

    private fun rotatedRect(
        centerX: Double,
        centerY: Double,
        rectWidth: Double,
        rectHeight: Double,
        angleDeg: Double,
    ): List<PaperCornerDetector.Point> {
        val angleRad = Math.toRadians(angleDeg)
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        val halfWidth = rectWidth / 2.0
        val halfHeight = rectHeight / 2.0
        val corners = listOf(
            -halfWidth to -halfHeight,
            halfWidth to -halfHeight,
            halfWidth to halfHeight,
            -halfWidth to halfHeight,
        )
        return corners.map { (x, y) ->
            val rotatedX = centerX + x * cosA - y * sinA
            val rotatedY = centerY + x * sinA + y * cosA
            PaperCornerDetector.Point(rotatedX.toFloat(), rotatedY.toFloat())
        }
    }

    private fun maskForQuad(
        width: Int,
        height: Int,
        corners: List<PaperCornerDetector.Point>,
    ): BooleanArray {
        val mask = BooleanArray(width * height)
        for (y in 0 until height) {
            val py = y + 0.5f
            for (x in 0 until width) {
                val px = x + 0.5f
                if (pointInConvexQuad(px, py, corners)) {
                    mask[y * width + x] = true
                }
            }
        }
        return mask
    }

    private fun pointInConvexQuad(
        px: Float,
        py: Float,
        corners: List<PaperCornerDetector.Point>,
    ): Boolean {
        var previousCross = 0f
        for (index in corners.indices) {
            val start = corners[index]
            val end = corners[(index + 1) % corners.size]
            val cross = (end.x - start.x) * (py - start.y) - (end.y - start.y) * (px - start.x)
            if (cross == 0f) continue
            if (previousCross == 0f) {
                previousCross = cross
                continue
            }
            if (cross * previousCross < 0f) return false
        }
        return true
    }
}