package com.baer.handtype.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.max

/**
 * Builds accented letters a template has no glyph for (é ñ ç à ô ...) from the base letter's glyph
 * plus marks drawn in the template's pen style. Characters with their own glyph never come here.
 */
internal object AccentComposer {

    enum class Mark { GRAVE, ACUTE, CIRCUMFLEX, TILDE, DIAERESIS, RING, CEDILLA }

    data class Decomposition(val base: Char, val marks: List<Mark>)

    data class Composed(val bitmap: Bitmap, val topPad: Int, val leftPad: Int)

    private val COMBINING = mapOf(
        '̀' to Mark.GRAVE,
        '́' to Mark.ACUTE,
        '̂' to Mark.CIRCUMFLEX,
        '̃' to Mark.TILDE,
        '̈' to Mark.DIAERESIS,
        '̊' to Mark.RING,
        '̧' to Mark.CEDILLA,
    )

    /** Base letter plus marks in NFD order, or null when [c] is not a letter with only supported marks. */
    fun decompose(c: Char): Decomposition? {
        val nfd = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        if (nfd.length < 2 || !nfd[0].isLetter()) return null
        val marks = nfd.drop(1).map { COMBINING[it] ?: return null }
        return Decomposition(nfd[0], marks)
    }

    /**
     * Draws the marks onto a copy of [base], a prepared glyph with its baseline at [baselineRow].
     * [unitPx] is the template's x-height in the same pixels and sets mark size and spacing.
     * The copy grows by [Composed.topPad] rows and [Composed.leftPad] columns to fit the marks.
     */
    fun compose(
        base: Bitmap,
        baselineRow: Int,
        decomposition: Decomposition,
        unitPx: Float,
        ink: PunctuationSynthesizer.InkStyle,
    ): Composed {
        val w = base.width
        val h = base.height
        val px = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)
        val u = unitPx
        val s = ink.strokeWidthPx
        val above = decomposition.marks.filter { it != Mark.CEDILLA }
        if (above.isNotEmpty() && decomposition.base in setOf('i', 'j')) removeTittle(px, w, h, baselineRow)

        val inkTop = (0 until h).firstOrNull { y -> (0 until w).any { x -> px[y * w + x] ushr 24 > 20 } } ?: 0
        val band = (u * 0.25f).toInt().coerceAtLeast(1)
        val topCenter = inkCenterX(px, w, inkTop, (inkTop + band).coerceAtMost(h - 1)) ?: w / 2f
        val bottomCenter = inkCenterX(px, w, (baselineRow - band).coerceAtLeast(0), baselineRow) ?: w / 2f

        val gap = u * 0.14f
        val stackGap = u * 0.08f
        val aboveHeight = above.sumOf { markHeight(it, u, s).toDouble() }.toFloat() +
            gap + stackGap * (above.size - 1).coerceAtLeast(0) + s
        val topPad = if (above.isEmpty()) 0 else max(0, ceil(aboveHeight - inkTop).toInt())
        val cedillaDepth = u * 0.34f + s
        val bottomPad = if (Mark.CEDILLA in decomposition.marks) {
            max(0, ceil(baselineRow + cedillaDepth - (h - 1)).toInt())
        } else {
            0
        }
        val halfWidth = u * 0.2f + s
        val leftPad = max(0, ceil(halfWidth - minOf(topCenter, bottomCenter)).toInt())
        val rightPad = max(0, ceil(maxOf(topCenter, bottomCenter) + halfWidth - w).toInt())

        val out = Bitmap.createBitmap(w + leftPad + rightPad, h + topPad + bottomPad, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, leftPad, topPad, w, h)
        val canvas = Canvas(out)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink.argb
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink.argb
            style = Paint.Style.STROKE
            strokeWidth = s
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val cx = topCenter + leftPad
        var bottom = inkTop + topPad - gap
        for (mark in above) {
            val mh = markHeight(mark, u, s)
            drawAbove(canvas, mark, cx, bottom, mh, u, s, fill, stroke)
            bottom -= mh + stackGap
        }
        if (Mark.CEDILLA in decomposition.marks) {
            drawCedilla(canvas, bottomCenter + leftPad, (baselineRow + topPad).toFloat(), u, s, stroke)
        }
        return Composed(out, topPad, leftPad)
    }

    private fun markHeight(mark: Mark, u: Float, s: Float): Float = when (mark) {
        Mark.ACUTE, Mark.GRAVE -> u * 0.28f
        Mark.CIRCUMFLEX -> u * 0.24f
        Mark.TILDE -> u * 0.14f
        Mark.DIAERESIS -> 2f * max(s, u * 0.05f)
        Mark.RING -> u * 0.24f
        Mark.CEDILLA -> 0f
    }

    private fun drawAbove(
        canvas: Canvas,
        mark: Mark,
        cx: Float,
        bottom: Float,
        mh: Float,
        u: Float,
        s: Float,
        fill: Paint,
        stroke: Paint,
    ) {
        val top = bottom - mh
        when (mark) {
            Mark.ACUTE -> canvas.drawLine(cx - u * 0.08f, bottom, cx + u * 0.08f, top, stroke)
            Mark.GRAVE -> canvas.drawLine(cx + u * 0.08f, bottom, cx - u * 0.08f, top, stroke)
            Mark.CIRCUMFLEX -> canvas.drawPath(
                Path().apply {
                    moveTo(cx - u * 0.15f, bottom)
                    lineTo(cx, top)
                    lineTo(cx + u * 0.15f, bottom)
                },
                stroke,
            )
            Mark.TILDE -> canvas.drawPath(
                Path().apply {
                    moveTo(cx - u * 0.19f, bottom - mh * 0.2f)
                    cubicTo(cx - u * 0.1f, top - mh * 0.6f, cx + u * 0.02f, bottom + mh * 0.6f, cx + u * 0.19f, top + mh * 0.2f)
                },
                stroke,
            )
            Mark.DIAERESIS -> {
                val r = max(s, u * 0.05f)
                canvas.drawCircle(cx - u * 0.13f, bottom - r, r, fill)
                canvas.drawCircle(cx + u * 0.13f, bottom - r, r, fill)
            }
            Mark.RING -> canvas.drawCircle(cx, bottom - mh / 2f, mh / 2f - s / 2f, stroke)
            Mark.CEDILLA -> Unit
        }
    }

    private fun drawCedilla(canvas: Canvas, cx: Float, baseline: Float, u: Float, s: Float, stroke: Paint) {
        // Hangs from the letter's foot: down, round to the right, and back left under the letter.
        val path = Path().apply {
            moveTo(cx, baseline - s / 2f)
            cubicTo(cx, baseline + u * 0.12f, cx + u * 0.17f, baseline + u * 0.14f, cx + u * 0.07f, baseline + u * 0.27f)
            quadTo(cx, baseline + u * 0.33f, cx - u * 0.1f, baseline + u * 0.3f)
        }
        canvas.drawPath(path, stroke)
    }

    /** Clears the dot of an i or j: the ink above the first blank row under the topmost ink. */
    private fun removeTittle(px: IntArray, w: Int, h: Int, baselineRow: Int) {
        fun rowHasInk(y: Int) = (0 until w).any { x -> px[y * w + x] ushr 24 > 20 }
        val first = (0 until h).firstOrNull(::rowHasInk) ?: return
        val gapRow = (first until baselineRow).firstOrNull { !rowHasInk(it) } ?: return
        if ((gapRow until h).none(::rowHasInk)) return
        px.fill(0, 0, gapRow * w)
    }

    private fun inkCenterX(px: IntArray, w: Int, fromRow: Int, toRow: Int): Float? {
        var minX = w
        var maxX = -1
        for (y in fromRow..toRow) {
            for (x in 0 until w) {
                if (px[y * w + x] ushr 24 > 20) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        return if (maxX >= minX) (minX + maxX) / 2f else null
    }
}
