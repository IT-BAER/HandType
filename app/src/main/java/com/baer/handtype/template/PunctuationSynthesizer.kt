package com.baer.handtype.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.baer.handtype.ml.GlyphPostProcessor
import kotlin.math.hypot
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

    /**
     * The writer's own pieces that punctuation is built from: an i dot and a straight stem. [lean] (x px per row going
     * up) and [section] (pen cross-section, [CursiveJoins.crossSection] steps) come from the stems of the tall letters,
     * so pen strokes drawn where the hand has no straight stem lean and fade like it; 0 and null when unknown.
     */
    class WriterMarks(val dot: Bitmap?, val stem: Bitmap?, val lean: Float = 0f, val section: FloatArray? = null) {
        companion object {
            val NONE = WriterMarks(null, null)

            fun from(glyphs: Map<Char, List<Bitmap>>): WriterMarks {
                val stems = "lbdhk".mapNotNull { c -> glyphs[c]?.firstOrNull()?.let(::stemShape) }
                return WriterMarks(
                    dot = "ijäöüÄÖÜ".firstNotNullOfOrNull { c -> glyphs[c]?.firstOrNull()?.let(::cropMark) },
                    // Only l: a 1 carries a flag and an I often serifs, which a ! or quote never has.
                    stem = glyphs['l']?.firstOrNull()?.let(::cropStem),
                    lean = stems.map { it.first }.sorted().getOrElse(stems.size / 2) { 0f },
                    section = if (stems.isEmpty()) null else FloatArray(CursiveJoins.SECTION_SIZE) { k ->
                        stems.map { it.second[k] }.sorted()[stems.size / 2]
                    },
                )
            }

            // Lean and cross-section of a tall letter's stem, from its rows above the bottom quarter of the x-height,
            // where it turns into a bowl, exit or foot.
            private fun stemShape(b: Bitmap): Pair<Float, FloatArray>? {
                val alpha = alpha(b)
                val top = alpha.indexOfFirst { it > INK_ALPHA }.takeIf { it >= 0 }?.div(b.width) ?: return null
                val rows = top..GlyphPostProcessor.canvasBaselineRow(b.height) - GUIDE_HEIGHT / 4
                val lean = stemLean(alpha, b.width, rows) ?: return null
                val norm = hypot(lean, 1f)
                val section = CursiveJoins.crossSection(
                    alpha, b.width, singleRunCentres(alpha, b.width, rows), lean / norm to -1f / norm,
                    CursiveJoins.penWidth(alpha, b.width) / 2 + 2f,
                )
                return lean to section
            }

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

    private fun alpha(b: Bitmap): IntArray =
        IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            .map { it ushr 24 }.toIntArray()

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

    /** Alpha-weighted ink centre (x, y) of each row in [rows] that holds a single ink run: a loop or bowl row holds two. */
    internal fun singleRunCentres(alpha: IntArray, width: Int, rows: IntRange): List<Pair<Float, Float>> = rows.mapNotNull { y ->
        val row = (0 until width).map { alpha[y * width + it] }
        val runs = row.indices.count { x -> row[x] > INK_ALPHA && (x == 0 || row[x - 1] <= INK_ALPHA) }
        if (runs != 1) return@mapNotNull null
        val ink = row.indices.filter { row[it] > INK_ALPHA }
        ink.sumOf { it * row[it] }.toFloat() / ink.sumOf { row[it] } to y.toFloat()
    }

    /**
     * How far a stroke moves right per row going up, from its single-run rows within [rows] of [alpha]
     * ([width] wide); null under 10 such rows.
     */
    internal fun stemLean(alpha: IntArray, width: Int, rows: IntRange): Float? =
        centreLine(singleRunCentres(alpha, width, rows))?.let { -it.second }

    /** Least-squares line x = a + b y (a to b) through [centres]; null under 10 of them. */
    private fun centreLine(centres: List<Pair<Float, Float>>): Pair<Float, Float>? {
        if (centres.size < 10) return null
        val mx = centres.map { it.first }.average()
        val my = centres.map { it.second }.average()
        val slope = centres.sumOf { (x, y) -> (x - mx) * (y - my) } / centres.sumOf { (_, y) -> (y - my) * (y - my) }
        return (mx - slope * my).toFloat() to slope.toFloat()
    }

    /** Cross-section of a plain pen [widthPx] wide: [alpha] across its width, fading out over one more pixel. */
    internal fun penSection(widthPx: Float, alpha: Int): FloatArray =
        FloatArray(CursiveJoins.SECTION_SIZE) { k -> alpha * (widthPx / 2 + 1 - k * CursiveJoins.SECTION_STEP).coerceIn(0f, 1f) }

    /**
     * Strokes [path] (pixel centres at +0.5) into [pixels] ([width] wide, un-premultiplied ARGB) with [section] around
     * it, ends round, [radius] px thicker each side. Each pixel keeps the darker ink, so a stroke never stacks on ink.
     */
    internal fun drawStroke(
        pixels: IntArray, width: Int, path: List<Pair<Float, Float>>, section: FloatArray, inkRgb: Int, radius: Float = 0f,
    ) {
        val height = pixels.size / width
        val reach = radius + CursiveJoins.SECTION_SIZE * CursiveJoins.SECTION_STEP
        val x0 = (path.minOf { it.first } - reach).toInt().coerceAtLeast(0)
        val x1 = (path.maxOf { it.first } + reach).toInt().coerceAtMost(width - 1)
        val y0 = (path.minOf { it.second } - reach).toInt().coerceAtLeast(0)
        val y1 = (path.maxOf { it.second } + reach).toInt().coerceAtMost(height - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val qx = x + 0.5f
            val qy = y + 0.5f
            val distance = if (path.size == 1) hypot(path[0].first - qx, path[0].second - qy) else path.zipWithNext().minOf { (a, b) ->
                val dx = b.first - a.first
                val dy = b.second - a.second
                val t = (((qx - a.first) * dx + (qy - a.second) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
                hypot(a.first + dx * t - qx, a.second + dy * t - qy)
            }
            val coverage = CursiveJoins.sectionAt(section, (distance - radius).coerceAtLeast(0f)).roundToInt()
            if (coverage > 0) pixels[y * width + x] = CursiveJoins.bridgePixel(pixels[y * width + x], coverage, inkRgb)
        }
    }

    private val SUPPORTED: Set<Char> = setOf(
        '.', ',', '!', '?', ':', ';', '\'', '"', '-',
    )

    fun supports(c: Char): Boolean = c in SUPPORTED

    fun synthesize(c: Char, ink: InkStyle = InkStyle.DEFAULT, marks: WriterMarks = WriterMarks.NONE): Bitmap? {
        if (c !in SUPPORTED) return null
        val inkStyle = ink
        val strokePx = inkStyle.strokeWidthPx
        val dotR = strokePx * 1.0f
        val xTop = inkStyle.xHeightTop.toFloat()
        val dot = marks.dot
        val stem = marks.stem
        // Tall marks follow the writer's tall letters: a row of the default layout, moved to this hand.
        val size = ((BASELINE - inkStyle.ascenderTop).toFloat() / (BASELINE - ASCENDER_TOP)).coerceIn(0.4f, 1.6f)
        fun sized(defaultRow: Float): Float = BASELINE - (BASELINE - defaultRow) * size
        // A ! leans along one line through its dot's centre: the writer stem's own centre line, else the hand's lean.
        val dotWidth = dot?.width?.toFloat() ?: (2 * dotR)
        val dotMiddle = BASELINE - (dot?.height?.toFloat() ?: (2 * dotR)) / 2
        val stemTop = sized(ASCENDER_TOP + 10f)
        val stemBottom = minOf(sized(BASELINE - 18f), 2 * dotMiddle - BASELINE - 8f)
        val bangStem = if (c == '!' && stem != null) stemPiece(stem, (stemBottom - stemTop).roundToInt()) else null
        // Rows of [bangStem] per canvas row, and its centre line x = a + b y in its own pixels.
        val squash = bangStem?.let { it.height / (stemBottom - stemTop) } ?: 1f
        val (lineA, lineB) = bangStem?.let { centreLine(singleRunCentres(alpha(it), it.width, 0 until it.height)) ?: ((it.width - 1) / 2f to 0f) }
            ?: (0f to -marks.lean)
        val lean = -lineB * squash
        val bangStemLeft = -0.5f - lineA - lineB * ((dotMiddle - stemTop) * squash - 0.5f)
        val (inkLeft, inkRight) = when {
            c != '!' -> 0f to 0f
            bangStem != null -> minOf(-dotWidth / 2, bangStemLeft) to maxOf(dotWidth / 2, bangStemLeft + bangStem.width)
            else -> listOf(-dotWidth / 2, dotWidth / 2, lean * (dotMiddle - stemTop), lean * (dotMiddle - stemBottom)).let { it.min() to it.max() }
        }
        val width = when (c) {
            '.', ',', '\'', ':', ';', '!' -> 40
            '"' -> 64
            '-' -> 90
            '?' -> 96
            else -> 60
        }.coerceAtLeast((marks.dot?.width ?: 0) + 16).coerceAtLeast((inkRight - inkLeft).roundToInt() + 16)
        val bmp = Bitmap.createBitmap(width, CANVAS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = width / 2f - (inkLeft + inkRight) / 2
        val filtered = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val section = marks.section ?: penSection(strokePx, inkStyle.argb ushr 24)
        // A pen stroke along [path] in the writer's section, [radius] px thicker each side.
        fun pen(path: List<Pair<Float, Float>>, radius: Float = 0f) {
            val px = IntArray(width * CANVAS).also { bmp.getPixels(it, 0, width, 0, 0, width, CANVAS) }
            drawStroke(px, width, path, section, inkStyle.argb and 0xFFFFFF, radius)
            bmp.setPixels(px, 0, width, 0, 0, width, CANVAS)
        }
        // The writer's dot when the hand has one, else a round pen dot; returns its top row.
        fun dotOn(bottom: Float): Float {
            if (dot == null) {
                pen(listOf(cx to bottom - dotR), radius = dotR - strokePx / 2)
                return bottom - 2 * dotR
            }
            val top = (bottom - dot.height).roundToInt()
            canvas.drawBitmap(dot, (cx - dot.width / 2f).roundToInt().toFloat(), top.toFloat(), null)
            return top.toFloat()
        }
        // A piece of the writer's stem between two rows, else a pen line leaning by [slant].
        fun stemBetween(x: Float, top: Float, bottom: Float, slant: Float = 0f) {
            if (stem == null) {
                pen(listOf(x + slant / 2 to top, x - slant / 2 to bottom))
            } else {
                val piece = stemPiece(stem, (bottom - top).roundToInt())
                val left = (x - piece.width / 2f).roundToInt().toFloat()
                canvas.drawBitmap(piece, null, RectF(left, top, left + piece.width, bottom), filtered)
            }
        }
        // A single tail curving down-left from just above the baseline, drawn over the dot when there is one.
        fun comma() = pen(quad(cx + 1f to BASELINE - 6f, cx + 2f to BASELINE + 2f, cx - 4f to BASELINE + 14f))
        when (c) {
            '.' -> dotOn(BASELINE.toFloat())
            ',' -> {
                if (dot != null) dotOn(BASELINE.toFloat())
                comma()
            }
            '!' -> {
                dotOn(BASELINE.toFloat())
                if (bangStem != null) {
                    val left = (cx + bangStemLeft).roundToInt().toFloat()
                    canvas.drawBitmap(bangStem, null, RectF(left, stemTop, left + bangStem.width, stemBottom), filtered)
                } else {
                    pen(listOf(cx + lean * (dotMiddle - stemTop) to stemTop, cx + lean * (dotMiddle - stemBottom) to stemBottom))
                }
            }
            '?' -> {
                val hookRadius = 18f * size
                val hookCenterY = sized(ASCENDER_TOP + 22f)
                val start = cx - hookRadius to hookCenterY + 4f * size
                val top = cx to hookCenterY - hookRadius
                val right = cx + hookRadius to hookCenterY
                pen(
                    listOf(start) + quad(start, cx - hookRadius to hookCenterY - hookRadius, top).drop(1) +
                        quad(top, cx + hookRadius to hookCenterY - hookRadius, right).drop(1) +
                        quad(right, cx + hookRadius to sized(X_HEIGHT_TOP + 22f), cx to sized(BASELINE - 26f)).drop(1),
                )
                dotOn(BASELINE.toFloat())
            }
            ':' -> {
                dotOn(xTop + (dot?.height?.toFloat() ?: (2 * dotR)))
                dotOn(BASELINE.toFloat())
            }
            ';' -> {
                dotOn(xTop + (dot?.height?.toFloat() ?: (2 * dotR)))
                if (dot != null) dotOn(BASELINE.toFloat())
                comma()
            }
            '\'' -> stemBetween(cx - 0.5f, xTop - 22f, xTop - 4f, slant = 5f)
            '"' -> {
                stemBetween(cx - 10.5f, xTop - 22f, xTop - 4f, slant = 5f)
                stemBetween(cx + 9.5f, xTop - 22f, xTop - 4f, slant = 5f)
            }
            '-' -> {
                val y = (xTop + BASELINE) / 2f
                if (stem == null) {
                    // Rising 6 degrees to the right, like the laid-down writer stem.
                    pen(listOf(cx - 20f to y + 2.1f, cx + 20f to y - 2.1f))
                } else {
                    // The stem laid down, rising slightly to the right as a dash usually does.
                    val piece = stemPiece(stem, 40)
                    canvas.save()
                    canvas.rotate(84f, cx, y)
                    canvas.drawBitmap(piece, null, RectF(cx - piece.width / 2f, y - 20f, cx + piece.width / 2f, y + 20f), filtered)
                    canvas.restore()
                }
            }
        }
        return bmp
    }

    /**
     * The middle [rows] of [stem], cut to the columns its ink covers there, so a short mark keeps the
     * stem's pen width and lean. A stem shorter than [rows] is returned whole and gets stretched.
     */
    private fun stemPiece(stem: Bitmap, rows: Int): Bitmap {
        if (rows >= stem.height || rows < 1) return stem
        val top = (stem.height - rows) / 2
        val px = IntArray(stem.width * rows).also { stem.getPixels(it, 0, stem.width, 0, top, stem.width, rows) }
        val columns = px.indices.filter { (px[it] ushr 24) > INK_ALPHA }.map { it % stem.width }
        if (columns.isEmpty()) return stem
        return Bitmap.createBitmap(stem, columns.min(), top, columns.max() - columns.min() + 1, rows)
    }

    /** A quadratic curve from [from] over [control] to [to] as 12 straight pieces. */
    private fun quad(from: Pair<Float, Float>, control: Pair<Float, Float>, to: Pair<Float, Float>): List<Pair<Float, Float>> =
        (0..12).map { i ->
            val t = i / 12f
            val u = 1 - t
            u * u * from.first + 2 * u * t * control.first + t * t * to.first to
                u * u * from.second + 2 * u * t * control.second + t * t * to.second
        }
}
