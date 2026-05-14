package com.baer.handtype.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.baer.handtype.ml.GlyphPostProcessor

/**
 * Synthesizes punctuation glyphs that match the visual style of normalized user-template
 * glyphs. Produces 220-row bitmaps with ink coordinates aligned to the standard baseline
 * ([GlyphPostProcessor.normalizedBaselineRow]) and guide band so they slot into the renderer
 * pipeline exactly like real user-captured glyphs.
 *
 * Used when a user template lacks a captured glyph for a common punctuation mark — instead of
 * skipping the character, we draw a reasonable approximation in the user's pen colour and
 * weight so the output text stays continuous.
 */
internal object PunctuationSynthesizer {

    private val CANVAS = GlyphPostProcessor.normalizedCanvasSize()       // 220
    private val BASELINE = GlyphPostProcessor.normalizedBaselineRow()     // 163
    private val GUIDE_HEIGHT = GlyphPostProcessor.normalizedGuideBandHeight() // 101
    private val X_HEIGHT_TOP = BASELINE - GUIDE_HEIGHT                    // 62
    private const val ASCENDER_TOP = 30

    /**
     * Style parameters sampled from the user's actual glyphs so synthesized punctuation
     * blends in. Use [InkStyle.estimate] over a few template bitmaps.
     */
    data class InkStyle(
        /** Stroke width in normalized-canvas pixels (typical user pen: 2-4). */
        val strokeWidthPx: Float,
        /** ARGB ink colour — alpha < 255 because GlyphPostProcessor emits anti-aliased ink. */
        val argb: Int,
    ) {
        companion object {
            val DEFAULT = InkStyle(strokeWidthPx = 3f, argb = 0xB0222222.toInt())

            fun estimate(glyphs: Collection<Bitmap>): InkStyle {
                if (glyphs.isEmpty()) return DEFAULT
                var alphaSum = 0L
                var rSum = 0L
                var gSum = 0L
                var bSum = 0L
                var n = 0L
                var widthRunSamples = 0
                var widthRunSum = 0
                // Sample up to 6 glyphs to keep cost bounded.
                for (bmp in glyphs.take(6)) {
                    val w = bmp.width
                    val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    for (y in 0 until h step 2) {
                        var runLen = 0
                        for (x in 0 until w) {
                            val p = px[y * w + x]
                            val a = p ushr 24
                            if (a >= 60) {
                                alphaSum += a
                                rSum += (p shr 16) and 0xFF
                                gSum += (p shr 8) and 0xFF
                                bSum += p and 0xFF
                                n++
                                runLen++
                            } else if (runLen in 1..12) {
                                widthRunSum += runLen
                                widthRunSamples++
                                runLen = 0
                            } else {
                                runLen = 0
                            }
                        }
                    }
                }
                if (n == 0L) return DEFAULT
                val avgA = (alphaSum / n).toInt().coerceIn(80, 240)
                val avgR = (rSum / n).toInt().coerceIn(0, 80)
                val avgG = (gSum / n).toInt().coerceIn(0, 80)
                val avgB = (bSum / n).toInt().coerceIn(0, 80)
                val argb = (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
                val strokeWidth = if (widthRunSamples > 0) {
                    (widthRunSum.toFloat() / widthRunSamples).coerceIn(2f, 6f)
                } else {
                    3f
                }
                return InkStyle(strokeWidthPx = strokeWidth, argb = argb)
            }
        }
    }

    private val SUPPORTED: Set<Char> = setOf(
        '.', ',', '!', '?', ':', ';', '\'', '"', '-',
    )

    fun supports(c: Char): Boolean = c in SUPPORTED

    fun synthesize(c: Char, ink: InkStyle = InkStyle.DEFAULT): Bitmap? {
        if (c !in SUPPORTED) return null
        val inkStyle = ink
        val strokePx = inkStyle.strokeWidthPx
        val width = when (c) {
            '.', ',', '\'', ':', ';', '!' -> 40
            '"' -> 64
            '-' -> 90
            '?' -> 96
            else -> 60
        }
        val bmp = Bitmap.createBitmap(width, CANVAS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkStyle.argb
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkStyle.argb
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val cx = width / 2f
        val dotR = strokePx * 1.0f
        when (c) {
            '.' -> canvas.drawCircle(cx, BASELINE - dotR, dotR, fill)
            ',' -> drawComma(canvas, cx, fill, stroke, dotR)
            '!' -> {
                canvas.drawLine(cx, ASCENDER_TOP + 10f, cx, BASELINE - 18f, stroke)
                canvas.drawCircle(cx, BASELINE - dotR, dotR, fill)
            }
            '?' -> {
                val hookRadius = 18f
                val hookCenterY = ASCENDER_TOP + 22f
                val path = Path().apply {
                    moveTo(cx - hookRadius, hookCenterY + 4f)
                    quadTo(cx - hookRadius, hookCenterY - hookRadius, cx, hookCenterY - hookRadius)
                    quadTo(cx + hookRadius, hookCenterY - hookRadius, cx + hookRadius, hookCenterY)
                    quadTo(cx + hookRadius, X_HEIGHT_TOP + 22f, cx, BASELINE - 26f)
                }
                canvas.drawPath(path, stroke)
                canvas.drawCircle(cx, BASELINE - dotR, dotR, fill)
            }
            ':' -> {
                canvas.drawCircle(cx, X_HEIGHT_TOP + 14f, dotR, fill)
                canvas.drawCircle(cx, BASELINE - dotR, dotR, fill)
            }
            ';' -> {
                canvas.drawCircle(cx, X_HEIGHT_TOP + 14f, dotR, fill)
                drawComma(canvas, cx, fill, stroke, dotR)
            }
            '\'' -> {
                canvas.drawLine(cx + 2, ASCENDER_TOP + 18f, cx - 3, X_HEIGHT_TOP + 6f, stroke)
            }
            '"' -> {
                canvas.drawLine(cx - 10 + 2, ASCENDER_TOP + 18f, cx - 10 - 3, X_HEIGHT_TOP + 6f, stroke)
                canvas.drawLine(cx + 10 + 2, ASCENDER_TOP + 18f, cx + 10 - 3, X_HEIGHT_TOP + 6f, stroke)
            }
            '-' -> {
                val y = (X_HEIGHT_TOP + GUIDE_HEIGHT / 2).toFloat()
                canvas.drawLine(cx - 20f, y, cx + 20f, y, stroke)
            }
        }
        return bmp
    }

    private fun drawComma(canvas: Canvas, cx: Float, fill: Paint, stroke: Paint, dotR: Float) {
        // Single curving tail — no separate dot. Starts just above baseline and
        // curves down-left, like a natural handwritten comma.
        val path = Path().apply {
            moveTo(cx + 1f, BASELINE - 6f)
            quadTo(cx + 2f, BASELINE + 2f, cx - 4f, BASELINE + 14f)
        }
        canvas.drawPath(path, stroke)
    }
}
