package com.baer.handtype.feature.capture

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Finds four paper corners from a binary paper mask.
 *
 * Instead of splitting points into centroid quadrants, this fits the paper's dominant axes,
 * derives an oriented bounding box in that local frame, and snaps each corner back to the
 * nearest real boundary pixel. That stays stable for strong sheet rotation and mild perspective.
 */
internal object PaperCornerDetector {

    data class Point(val x: Float, val y: Float)

    data class Corners(
        val topLeft: Point,
        val topRight: Point,
        val bottomRight: Point,
        val bottomLeft: Point,
    )

    fun detect(paperMask: BooleanArray, width: Int, height: Int): Corners? {
        if (width <= 1 || height <= 1 || paperMask.size != width * height) return null

        var sumX = 0.0
        var sumY = 0.0
        var pointCount = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!paperMask[row + x]) continue
                sumX += x.toDouble()
                sumY += y.toDouble()
                pointCount++
            }
        }
        if (pointCount < 4) return null

        val centerX = sumX / pointCount
        val centerY = sumY / pointCount

        var covXX = 0.0
        var covXY = 0.0
        var covYY = 0.0
        val boundary = ArrayList<ProjectedPoint>()
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!paperMask[row + x]) continue
                val dx = x - centerX
                val dy = y - centerY
                covXX += dx * dx
                covXY += dx * dy
                covYY += dy * dy

                if (isBoundaryPixel(paperMask, width, height, x, y)) {
                    boundary += ProjectedPoint(
                        point = Point(x.toFloat(), y.toFloat()),
                        axisU = 0.0,
                        axisV = 0.0,
                    )
                }
            }
        }
        if (boundary.size < 4) return null

        val theta = 0.5 * atan2(2.0 * covXY, covXX - covYY)
        val axisU = Axis(cos(theta), sin(theta))
        val axisV = Axis(-sin(theta), cos(theta))

        var minU = Double.POSITIVE_INFINITY
        var maxU = Double.NEGATIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        for (index in boundary.indices) {
            val point = boundary[index].point
            val dx = point.x - centerX
            val dy = point.y - centerY
            val u = dx * axisU.x + dy * axisU.y
            val v = dx * axisV.x + dy * axisV.y
            boundary[index] = boundary[index].copy(axisU = u, axisV = v)
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }

        val used = HashSet<Int>(4)
        val candidates = listOf(
            pickNearest(boundary, minU, minV, used),
            pickNearest(boundary, maxU, minV, used),
            pickNearest(boundary, maxU, maxV, used),
            pickNearest(boundary, minU, maxV, used),
        )
        if (candidates.any { it == null }) return null

        val corners = candidates.filterNotNull().map { it.point }
        return orderCorners(corners)
    }

    private fun isBoundaryPixel(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return true
        val idx = y * width + x
        return !mask[idx - 1] || !mask[idx + 1] || !mask[idx - width] || !mask[idx + width]
    }

    private fun pickNearest(
        points: List<ProjectedPoint>,
        targetU: Double,
        targetV: Double,
        used: MutableSet<Int>,
    ): ProjectedPoint? {
        var bestIndex = -1
        var bestDistance = Double.POSITIVE_INFINITY
        for (index in points.indices) {
            if (index in used) continue
            val point = points[index]
            val du = point.axisU - targetU
            val dv = point.axisV - targetV
            val distance = du * du + dv * dv
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        if (bestIndex < 0) return null
        used += bestIndex
        return points[bestIndex]
    }

    private fun orderCorners(points: List<Point>): Corners? {
        if (points.size != 4) return null

        val cx = points.map { it.x }.average()
        val cy = points.map { it.y }.average()
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }

        return Corners(
            topLeft = sorted[0],
            topRight = sorted[1],
            bottomRight = sorted[2],
            bottomLeft = sorted[3],
        )
    }

    private data class Axis(val x: Double, val y: Double)

    private data class ProjectedPoint(
        val point: Point,
        val axisU: Double,
        val axisV: Double,
    )
}