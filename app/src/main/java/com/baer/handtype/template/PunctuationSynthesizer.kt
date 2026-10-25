package com.baer.handtype.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.baer.handtype.ml.GlyphPostProcessor
import kotlin.math.roundToInt

/**
 * Synthesizes punctuation glyphs that match the visual style of normalized user-template
 * glyphs. Produces 220-row bitmaps with ink coordinates aligned to the standard baseline
 * ([GlyphPostProcessor.normalizedBaselineRow]) and guide band so they slot into the renderer
 * pipeline exactly like real user-captured glyphs.
 *
 * Used when a user template lacks a captured glyph for a common punctuation mark — instead of
 * skipping the character, we draw a reasonable approximation in the user's pen colour and
 * weight so the output text stays continuous. Dots are the writer's own i dot and stems the
 * writer's own straight l ([WriterMarks]) when the hand has them.
 */
internal object PunctuationSynthesizer {

    private val CANVAS = GlyphPostProcessor.normalizedCanvasSize()       // 220
    private val BASELINE = GlyphPostProcessor.normalizedBaselineRow()     // 163
    private val GUIDE_HEIGHT = GlyphPostProcessor.normalizedGuideBandHeight() // 101
    private val X_HEIGHT_TOP = BASELINE - GUIDE_HEIGHT                    // 62
    // Tall marks (!, ?, quotes) start just under the cap guide so they match capital height.
    private val ASCENDER_TOP = X_HEIGHT_TOP + 8

    /**
     * Style parameters sampled from the user's actual glyphs so synthesized punctuation
     * blends in. Use [InkStyle.estimate] over a few template bitmaps.
     */
    data class InkStyle(
        /** Stroke width in normalized-canvas pixels (typical user pen: 2-4). */
        val strokeWidthPx: Float,
        /** ARGB ink colour — alpha < 255 because GlyphPostProcessor emits anti-aliased ink. */
        val argb: Int,
        /** Canvas row where this writer's x-height letters start; marks hang from it. */
        val xHeightTop: Int = X_HEIGHT_TOP,
        /** Canvas row where this writer's tall letters and digits start; ! and ? match them. */
        val ascenderTop: Int = ASCENDER_TOP,
    ) {
        companion object {
            val DEFAULT = InkStyle(strokeWidthPx = 3f, argb = 0xB0222222.toInt())

            fun estimate(glyphs: Collection<Bitmap>): InkStyle {
                if (glyphs.isEmpty()) return DEFAULT
                val alphaHistogram = IntArray(256)
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
                                alphaHistogram[a]++
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
                // A drawn stroke has one alpha across its core, so it takes the writer's core alpha:
                // the mean of all ink includes the faint stroke edges and draws too light.
                var seen = 0L
                val avgA = (60..255).first { seen += alphaHistogram[it]; seen >= n * 0.9 }.coerceIn(80, 240)
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

    /** The writer's own pieces that punctuation is built from: an i dot and a straight stem. */
    class WriterMarks(val dot: Bitmap?, val stem: Bitmap?) {
        companion object {
            val NONE = WriterMarks(null, null)

            fun from(glyphs: Map<Char, List<Bitmap>>): WriterMarks = WriterMarks(
                dot = "ijäöüÄÖÜ".firstNotNullOfOrNull { c -> glyphs[c]?.firstOrNull()?.let(::cropMark) },
                // Only l: a 1 carries a flag and an I often serifs, which a ! or quote never has.
                stem = glyphs['l']?.firstOrNull()?.let(::cropStem),
            )

            private fun alpha(b: Bitmap): IntArray =
                IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
                    .map { it ushr 24 }.toIntArray()

            private fun cropMark(b: Bitmap): Bitmap? = markBounds(alpha(b), b.width)?.let { (l, t, r, bottom) ->
                Bitmap.createBitmap(b, l, t, r - l + 1, bottom - t + 1)
            }

            private fun cropStem(b: Bitmap): Bitmap? {
                val ink = alpha(b).withIndex().filter { it.value > INK_ALPHA }.map { it.index }
                if (ink.isEmpty()) return null
                val l = ink.minOf { it % b.width }
                val r = ink.maxOf { it % b.width }
                val t = ink.first() / b.width
                val bottom = ink.last() / b.width
                val rows = ink.groupBy { it / b.width }
                val extents = IntArray(bottom - t + 1) { y ->
                    rows[t + y]?.let { row -> row.maxOf { it % b.width } - row.minOf { it % b.width } + 1 } ?: 0
                }
                if (!isStemLike(extents)) return null
                return Bitmap.createBitmap(b, l, t, r - l + 1, bottom - t + 1)
            }
        }
    }

    private const val INK_ALPHA = 40

    /**
     * Box (left, top, right, bottom) of the largest piece of ink above the letter body in [alpha],
     * rows [width] wide: an i dot or one umlaut dot. The body is the lowest large piece.
     */
    internal fun markBounds(alpha: IntArray, width: Int): IntArray? {
        val height = alpha.size / width
        val seen = BooleanArray(alpha.size)
        val pieces = mutableListOf<IntArray>() // left, top, right, bottom, area
        val queue = ArrayDeque<Int>()
        for (start in alpha.indices) {
            if (alpha[start] <= INK_ALPHA || seen[start]) continue
            val piece = intArrayOf(width, height, -1, -1, 0).also { pieces += it }
            seen[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val x = i % width
                val y = i / width
                piece[0] = minOf(piece[0], x); piece[1] = minOf(piece[1], y)
                piece[2] = maxOf(piece[2], x); piece[3] = maxOf(piece[3], y)
                piece[4]++
                for (ny in y - 1..y + 1) for (nx in x - 1..x + 1) {
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val j = ny * width + nx
                    if (alpha[j] > INK_ALPHA && !seen[j]) { seen[j] = true; queue.add(j) }
                }
            }
        }
        val largest = pieces.maxOfOrNull { it[4] } ?: return null
        val body = pieces.filter { it[4] * 2 >= largest }.maxBy { it[3] }
        return pieces.filter { it[3] < body[1] && it[4] > 3 }.maxByOrNull { it[4] }?.copyOf(4)
    }

    /**
     * A single straight stroke tall enough to stretch into a ! or a quote: [rowExtents] (ink width
     * of each row) stay near the pen width. A loop, flag or curl doubles them over many rows.
     */
    internal fun isStemLike(rowExtents: IntArray): Boolean {
        if (rowExtents.size < 40) return false
        val sorted = rowExtents.sorted()
        return sorted[(sorted.size * 0.97f).toInt()] <= sorted[sorted.size / 2] * 2
    }

    private val SUPPORTED: Set<Char> = setOf(
        '.', ',', '!', '?', ':', ';', '\'', '"', '-',
    )

    fun supports(c: Char): Boolean = c in SUPPORTED

    fun synthesize(c: Char, ink: InkStyle = InkStyle.DEFAULT, marks: WriterMarks = WriterMarks.NONE): Bitmap? {
        if (c !in SUPPORTED) return null
        val inkStyle = ink
        val strokePx = inkStyle.strokeWidthPx
        val width = when (c) {
            '.', ',', '\'', ':', ';', '!' -> 40
            '"' -> 64
            '-' -> 90
            '?' -> 96
            else -> 60
        }.coerceAtLeast((marks.dot?.width ?: 0) + 16)
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
        val xTop = inkStyle.xHeightTop.toFloat()
        val dot = marks.dot
        val stem = marks.stem
        // Tall marks follow the writer's tall letters: a row of the default layout, moved to this hand.
        val size = ((BASELINE - inkStyle.ascenderTop).toFloat() / (BASELINE - ASCENDER_TOP)).coerceIn(0.4f, 1.6f)
        fun sized(defaultRow: Float): Float = BASELINE - (BASELINE - defaultRow) * size
        val filtered = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // The writer's dot when the hand has one, else a round pen dot; returns its top row.
        fun dotOn(bottom: Float): Float {
            if (dot == null) {
                canvas.drawCircle(cx, bottom - dotR, dotR, fill)
                return bottom - 2 * dotR
            }
            val top = (bottom - dot.height).roundToInt()
            canvas.drawBitmap(dot, (cx - dot.width / 2f).roundToInt().toFloat(), top.toFloat(), null)
            return top.toFloat()
        }
        // The writer's stem stretched between two rows, else a pen line leaning by [slant].
        fun stemBetween(x: Float, top: Float, bottom: Float, slant: Float = 0f) {
            if (stem == null) {
                canvas.drawLine(x + slant / 2, top, x - slant / 2, bottom, stroke)
            } else {
                val left = (x - stem.width / 2f).roundToInt().toFloat()
                canvas.drawBitmap(stem, null, RectF(left, top, left + stem.width, bottom), filtered)
            }
        }
        when (c) {
            '.' -> dotOn(BASELINE.toFloat())
            ',' -> {
                if (dot != null) dotOn(BASELINE.toFloat())
                drawComma(canvas, cx, fill, stroke, dotR)
            }
            '!' -> {
                val dotTop = dotOn(BASELINE.toFloat())
                stemBetween(cx, sized(ASCENDER_TOP + 10f), minOf(sized(BASELINE - 18f), dotTop - 8f))
            }
            '?' -> {
                val hookRadius = 18f * size
                val hookCenterY = sized(ASCENDER_TOP + 22f)
                val path = Path().apply {
                    moveTo(cx - hookRadius, hookCenterY + 4f * size)
                    quadTo(cx - hookRadius, hookCenterY - hookRadius, cx, hookCenterY - hookRadius)
                    quadTo(cx + hookRadius, hookCenterY - hookRadius, cx + hookRadius, hookCenterY)
                    quadTo(cx + hookRadius, sized(X_HEIGHT_TOP + 22f), cx, sized(BASELINE - 26f))
                }
                canvas.drawPath(path, stroke)
                dotOn(BASELINE.toFloat())
            }
            ':' -> {
                dotOn(xTop + (dot?.height?.toFloat() ?: (2 * dotR)))
                dotOn(BASELINE.toFloat())
            }
            ';' -> {
                dotOn(xTop + (dot?.height?.toFloat() ?: (2 * dotR)))
                if (dot != null) dotOn(BASELINE.toFloat())
                drawComma(canvas, cx, fill, stroke, dotR)
            }
            '\'' -> stemBetween(cx - 0.5f, xTop - 22f, xTop - 4f, slant = 5f)
            '"' -> {
                stemBetween(cx - 10.5f, xTop - 22f, xTop - 4f, slant = 5f)
                stemBetween(cx + 9.5f, xTop - 22f, xTop - 4f, slant = 5f)
            }
            '-' -> {
                val y = (xTop + BASELINE) / 2f
                if (stem == null) {
                    canvas.drawLine(cx - 20f, y, cx + 20f, y, stroke)
                } else {
                    // The stem laid down, rising slightly to the right as a dash usually does.
                    canvas.save()
                    canvas.rotate(84f, cx, y)
                    canvas.drawBitmap(stem, null, RectF(cx - stem.width / 2f, y - 20f, cx + stem.width / 2f, y + 20f), filtered)
                    canvas.restore()
                }
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
