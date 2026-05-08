package com.baer.handtype.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cleans raw OCR glyph crops (paper background + ink) into renderable glyph bitmaps.
 *
 * Pipeline:
 * 1. Grayscale + Otsu threshold to separate ink from paper.
 * 2. Trim to the ink pixel bounding box (drop OCR padding + paper rectangles).
 * 3. Re-emit as ARGB_8888 where paper = transparent and ink = solid black with
 *    intensity-derived alpha (so anti-aliased ink edges survive).
 * 4. Normalize onto a uniform [TARGET_CANVAS] square with the glyph bottom
 *    aligned to a fixed baseline so the bitmap renderer's averaged metrics work.
 */
object GlyphPostProcessor {

    private data class InkComponent(
        val pixels: IntArray,
        val area: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
        val centerX: Float get() = (left + right) / 2f
    }

    private const val TARGET_CANVAS = 220
    private const val TARGET_GLYPH_HEIGHT = 199
    private const val TARGET_BASELINE_Y = 175
    private const val INK_COLOR = 0xFF1A1410.toInt()
    private const val SIDE_PAD = 8

    /**
     * Returns a cleaned, baseline-aligned 350×350 ARGB glyph bitmap.
     *
     * Falls back to the original [crop] if the glyph cannot be processed (e.g. blank crop).
     */
    fun cleanGlyph(crop: Bitmap): Bitmap {
        val width = crop.width
        val height = crop.height
        if (width <= 0 || height <= 0) return crop

        val pixels = IntArray(width * height)
        crop.getPixels(pixels, 0, width, 0, 0, width, height)

        val luma = IntArray(pixels.size)
        var lumaMin = 255
        var lumaMax = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Standard luminance approximation (Rec. 601).
            val y = (r * 299 + g * 587 + b * 114) / 1000
            luma[i] = y
            if (y < lumaMin) lumaMin = y
            if (y > lumaMax) lumaMax = y
        }

        if (lumaMax - lumaMin < MIN_CONTRAST) {
            // Uniform/blank crop — return transparent canvas so hasInk() correctly returns false.
            return Bitmap.createBitmap(TARGET_CANVAS, TARGET_CANVAS, Bitmap.Config.ARGB_8888)
        }

        val threshold = otsuThreshold(luma)
        val inkMask = BooleanArray(pixels.size) { index -> luma[index] <= threshold }
        val components = extractInkComponents(inkMask, width, height)
        if (components.isEmpty()) return crop
        val primary = components.maxByOrNull { it.area } ?: return crop
        val keptComponents = components.filter { shouldKeepComponent(it, primary) }
        val filteredInkMask = BooleanArray(inkMask.size)
        keptComponents.forEach { component ->
            component.pixels.forEach { pixelIndex ->
                filteredInkMask[pixelIndex] = true
            }
        }

        // Bounding box of ink pixels.
        var top = height
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                if (filteredInkMask[rowOffset + x]) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (bottom < 0) return crop // no ink found

        val padding = 4
        left = (left - padding).coerceAtLeast(0)
        top = (top - padding).coerceAtLeast(0)
        right = (right + padding).coerceAtMost(width - 1)
        bottom = (bottom + padding).coerceAtMost(height - 1)

        val inkWidth = right - left + 1
        val inkHeight = bottom - top + 1
        if (inkWidth <= 0 || inkHeight <= 0) return crop

        // Build alpha-masked glyph (paper transparent, ink near-black).
        // Anything brighter than the Otsu threshold is treated as paper and emitted fully transparent;
        // pixels at-or-below the threshold are mapped to opacity proportional to their darkness.
        val masked = Bitmap.createBitmap(inkWidth, inkHeight, Bitmap.Config.ARGB_8888)
        val maskedPixels = IntArray(inkWidth * inkHeight)
        val darkSpan = (threshold - lumaMin).coerceAtLeast(1)
        for (y in 0 until inkHeight) {
            val srcRow = (top + y) * width
            val dstRow = y * inkWidth
            for (x in 0 until inkWidth) {
                val l = luma[srcRow + (left + x)]
                val alpha = if (!filteredInkMask[srcRow + (left + x)] || l > threshold) {
                    0
                } else {
                    // l <= threshold: darker than threshold means more ink. Clamp at 255.
                    (((threshold - l).toFloat() / darkSpan) * 255f)
                        .toInt()
                        .coerceIn(0, 255)
                }
                maskedPixels[dstRow + x] = if (alpha == 0) {
                    Color.TRANSPARENT
                } else {
                    (alpha shl 24) or (INK_COLOR and 0x00FFFFFF)
                }
            }
        }
        masked.setPixels(maskedPixels, 0, inkWidth, 0, 0, inkWidth, inkHeight)

        return placeOnCanvas(masked, preserveComponentSeparation = keptComponents.size > 1)
    }

    private fun extractInkComponents(mask: BooleanArray, width: Int, height: Int): List<InkComponent> {
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val components = mutableListOf<InkComponent>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var stackSize = 0
            stack[stackSize++] = start
            visited[start] = true

            val pixels = ArrayList<Int>()
            var left = width
            var top = height
            var right = -1
            var bottom = -1

            while (stackSize > 0) {
                val index = stack[--stackSize]
                pixels += index

                val x = index % width
                val y = index / width
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y

                val minY = maxOf(0, y - 1)
                val maxY = minOf(height - 1, y + 1)
                val minX = maxOf(0, x - 1)
                val maxX = minOf(width - 1, x + 1)
                for (ny in minY..maxY) {
                    val rowOffset = ny * width
                    for (nx in minX..maxX) {
                        if (nx == x && ny == y) continue
                        val neighbor = rowOffset + nx
                        if (!mask[neighbor] || visited[neighbor]) continue
                        visited[neighbor] = true
                        stack[stackSize++] = neighbor
                    }
                }
            }

            components += InkComponent(
                pixels = pixels.toIntArray(),
                area = pixels.size,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
        }

        return components
    }

    private fun shouldKeepComponent(component: InkComponent, primary: InkComponent): Boolean {
        if (component == primary) return true

        return shouldKeepComponentBounds(
            componentLeft = component.left,
            componentTop = component.top,
            componentRight = component.right,
            componentBottom = component.bottom,
            componentArea = component.area,
            primaryLeft = primary.left,
            primaryTop = primary.top,
            primaryRight = primary.right,
            primaryBottom = primary.bottom,
            primaryArea = primary.area,
        )
    }

    internal fun shouldKeepComponentBounds(
        componentLeft: Int,
        componentTop: Int,
        componentRight: Int,
        componentBottom: Int,
        componentArea: Int,
        primaryLeft: Int,
        primaryTop: Int,
        primaryRight: Int,
        primaryBottom: Int,
        primaryArea: Int,
    ): Boolean {
        val componentWidth = componentRight - componentLeft + 1
        val componentHeight = componentBottom - componentTop + 1
        val primaryWidth = primaryRight - primaryLeft + 1
        val primaryHeight = primaryBottom - primaryTop + 1

        val componentAspectRatio = componentWidth.toFloat() / componentHeight.coerceAtLeast(1)
        if (componentAspectRatio > 3.4f && componentHeight < maxOf(primaryHeight / 3, 10)) {
            return false
        }

        val horizontalGap = axisGap(componentLeft, componentRight, primaryLeft, primaryRight)
        val verticalGap = axisGap(componentTop, componentBottom, primaryTop, primaryBottom)
        val componentCenterX = (componentLeft + componentRight) / 2f
        val alignedWithPrimary = componentCenterX >= (primaryLeft - 12) && componentCenterX <= (primaryRight + 12)

        val sitsAbovePrimary = componentBottom < primaryTop
        if (sitsAbovePrimary) {
            val maxGap = maxOf(primaryHeight / 2, 24)
            val maxArea = maxOf(primaryArea / 2, 24)
            return alignedWithPrimary && verticalGap <= maxGap && componentArea <= maxArea
        }

        val closeHorizontally = horizontalGap <= maxOf(primaryWidth / 2, 12)
        val closeVertically = verticalGap <= maxOf(primaryHeight / 3, 14)
        val minArea = maxOf(primaryArea / 10, 8)
        val maxArea = maxOf((primaryArea * 0.95f).toInt(), 24)
        return closeHorizontally && closeVertically && componentArea in minArea..maxArea
    }

    private fun axisGap(startA: Int, endA: Int, startB: Int, endB: Int): Int {
        return when {
            endA < startB -> startB - endA
            endB < startA -> startA - endB
            else -> 0
        }
    }

    /** Renders the trimmed alpha glyph onto a canvas with baseline alignment.
     *
     * Canvas height is [TARGET_CANVAS] (for consistent baseline math in the renderer).
     * Canvas width is trimmed to the actual ink width + [SIDE_PAD] on each side, so
     * [HandwritingBitmapRenderer] advances by the real character width rather than the full
     * square canvas — eliminating the excessive inter-character gap in user-captured templates.
     */
    private fun placeOnCanvas(glyph: Bitmap, preserveComponentSeparation: Boolean = false): Bitmap {
        // Scale glyph to target glyph height, preserving aspect ratio.
        val srcH = glyph.height.toFloat()
        val srcW = glyph.width.toFloat()
        val targetH = TARGET_GLYPH_HEIGHT.toFloat()
        val scale = targetH / srcH
        val scaledW = (srcW * scale).roundToInt().coerceAtMost(TARGET_CANVAS)
        val scaledH = TARGET_GLYPH_HEIGHT

        // Width-trim: use actual ink width + small side padding so the renderer's
        // glyph.width advance reflects the true character width.
        val canvasWidth = (scaledW + 2 * SIDE_PAD).coerceAtMost(TARGET_CANVAS)
        val canvasBmp = Bitmap.createBitmap(canvasWidth, TARGET_CANVAS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)

        val left = SIDE_PAD.toFloat()
        // Bottom of glyph aligned to baseline.
        val top = (TARGET_BASELINE_Y - scaledH).toFloat()
        val dst = RectF(left, max(0f, top), left + scaledW, max(0f, top) + scaledH)
        val src = Rect(0, 0, glyph.width, glyph.height)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = !preserveComponentSeparation
        }
        canvas.drawBitmap(glyph, src, dst, paint)
        return canvasBmp
    }

    /**
     * Otsu's method for automatic luminance thresholding.
     *
     * Returns a luma value such that pixels at-or-below it are treated as ink.
     */
    private fun otsuThreshold(luma: IntArray): Int {
        val histogram = IntArray(256)
        luma.forEach { histogram[it]++ }
        val total = luma.size
        var sum = 0L
        for (t in 0..255) sum += (t * histogram[t]).toLong()

        var sumB = 0L
        var wB = 0
        var maxBetween = 0.0
        var threshold = 127
        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += (t * histogram[t]).toLong()
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxBetween) {
                maxBetween = between
                threshold = t
            }
        }
        return threshold
    }

    private const val MIN_CONTRAST = 30
}
