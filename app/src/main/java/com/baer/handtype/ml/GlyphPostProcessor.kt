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

        // Bounding box of ink pixels.
        var top = height
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                if (luma[rowOffset + x] <= threshold) {
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
                val alpha = if (l > threshold) {
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

        return placeOnCanvas(masked)
    }

    /** Renders the trimmed alpha glyph onto a canvas with baseline alignment.
     *
     * Canvas height is [TARGET_CANVAS] (for consistent baseline math in the renderer).
     * Canvas width is trimmed to the actual ink width + [SIDE_PAD] on each side, so
     * [HandwritingBitmapRenderer] advances by the real character width rather than the full
     * square canvas — eliminating the excessive inter-character gap in user-captured templates.
     */
    private fun placeOnCanvas(glyph: Bitmap): Bitmap {
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

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
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
