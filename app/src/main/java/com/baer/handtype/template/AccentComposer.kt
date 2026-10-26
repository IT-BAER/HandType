package com.baer.handtype.template

import android.graphics.Bitmap
import android.graphics.Canvas
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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
        marks: PunctuationSynthesizer.WriterMarks = PunctuationSynthesizer.WriterMarks.NONE,
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
        // Marks are pen strokes in the writer's cross-section, and dots the writer's own i dot, as punctuation is.
        val pixels = IntArray(out.width * out.height).also { out.getPixels(it, 0, out.width, 0, 0, out.width, out.height) }
        val section = marks.section ?: PunctuationSynthesizer.penSection(s, ink.argb ushr 24)
        val dots = mutableListOf<Pair<Float, Float>>()
        val pen = Pen { path, radius -> PunctuationSynthesizer.drawStroke(pixels, out.width, path, section, ink.argb and 0xFFFFFF, radius) }

        val cx = topCenter + leftPad
        var bottom = inkTop + topPad - gap
        for (mark in above) {
            val mh = markHeight(mark, u, s)
            drawAbove(pen, dots, mark, cx, bottom, mh, u, s)
            bottom -= mh + stackGap
        }
        if (Mark.CEDILLA in decomposition.marks) {
            drawCedilla(pen, bottomCenter + leftPad, (baselineRow + topPad).toFloat(), u, s)
        }
        val dot = marks.dot
        // Without a writer's dot, a round pen dot of the umlaut's size.
        if (dot == null) dots.forEach { (x, y) -> pen.stroke(listOf(x to y), max(s, u * 0.05f) - s / 2) }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        if (dot != null) {
            val canvas = Canvas(out)
            dots.forEach { (x, y) -> canvas.drawBitmap(dot, (x - dot.width / 2f).roundToInt().toFloat(), (y - dot.height / 2f).roundToInt().toFloat(), null) }
        }
        return Composed(out, topPad, leftPad)
    }

    /** A pen stroke along a path of points, [radius] px thicker each side. */
    private fun interface Pen {
        fun stroke(path: List<Pair<Float, Float>>, radius: Float)
    }

    private fun Pen.stroke(path: List<Pair<Float, Float>>) = stroke(path, 0f)

    /** [count] + 1 points along a cubic Bezier curve. */
    private fun cubic(p0: Pair<Float, Float>, p1: Pair<Float, Float>, p2: Pair<Float, Float>, p3: Pair<Float, Float>, count: Int = 16) =
        (0..count).map { k ->
            val t = k.toFloat() / count
            val v = 1 - t
            (v * v * v * p0.first + 3 * v * v * t * p1.first + 3 * v * t * t * p2.first + t * t * t * p3.first) to
                (v * v * v * p0.second + 3 * v * v * t * p1.second + 3 * v * t * t * p2.second + t * t * t * p3.second)
        }

    private fun markHeight(mark: Mark, u: Float, s: Float): Float = when (mark) {
        Mark.ACUTE, Mark.GRAVE -> u * 0.28f
        Mark.CIRCUMFLEX -> u * 0.24f
        Mark.TILDE -> u * 0.14f
        Mark.DIAERESIS -> 2f * max(s, u * 0.05f)
        Mark.RING -> u * 0.24f
        Mark.CEDILLA -> 0f
    }

    /** Strokes [mark] with [pen]; a diaeresis adds its two dot centres to [dots] instead. */
    private fun drawAbove(pen: Pen, dots: MutableList<Pair<Float, Float>>, mark: Mark, cx: Float, bottom: Float, mh: Float, u: Float, s: Float) {
        val top = bottom - mh
        when (mark) {
            Mark.ACUTE -> pen.stroke(listOf(cx - u * 0.08f to bottom, cx + u * 0.08f to top))
            Mark.GRAVE -> pen.stroke(listOf(cx + u * 0.08f to bottom, cx - u * 0.08f to top))
            Mark.CIRCUMFLEX -> pen.stroke(listOf(cx - u * 0.15f to bottom, cx to top, cx + u * 0.15f to bottom))
            Mark.TILDE -> pen.stroke(
                cubic(cx - u * 0.19f to bottom - mh * 0.2f, cx - u * 0.1f to top - mh * 0.6f, cx + u * 0.02f to bottom + mh * 0.6f, cx + u * 0.19f to top + mh * 0.2f),
            )
            Mark.DIAERESIS -> {
                val r = max(s, u * 0.05f)
                dots += cx - u * 0.13f to bottom - r
                dots += cx + u * 0.13f to bottom - r
            }
            Mark.RING -> {
                val r = mh / 2f - s / 2f
                pen.stroke((0..24).map { k -> (cx + r * cos(2 * PI * k / 24).toFloat()) to (bottom - mh / 2f + r * sin(2 * PI * k / 24).toFloat()) })
            }
            Mark.CEDILLA -> Unit
        }
    }

    private fun drawCedilla(pen: Pen, cx: Float, baseline: Float, u: Float, s: Float) {
        // Hangs from the letter's foot: down, round to the right, and back left under the letter.
        val end = cx + u * 0.07f to baseline + u * 0.27f
        val back = cx - u * 0.1f to baseline + u * 0.3f
        val control = cx to baseline + u * 0.33f
        pen.stroke(
            cubic(cx to baseline - s / 2f, cx to baseline + u * 0.12f, cx + u * 0.17f to baseline + u * 0.14f, end) +
                (1..8).map { k ->
                    val t = k / 8f
                    val v = 1 - t
                    (v * v * end.first + 2 * v * t * control.first + t * t * back.first) to (v * v * end.second + 2 * v * t * control.second + t * t * back.second)
                },
        )
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
