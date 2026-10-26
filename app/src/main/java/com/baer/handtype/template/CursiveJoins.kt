package com.baer.handtype.template

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Geometry for joining the letters of a cursive hand. Each letter was written alone on the sheet,
 * so it starts with a lead-in from the baseline, often with a pen-down blob, and ends with an exit
 * tail rising to the right. Set side by side, the blob sits under the previous tail like a dot.
 * Inside a word the previous tail takes the lead-in's place: the renderer drops the lead-in up to the
 * first point ahead of the tail's end and bridges the gap with a stroke that runs on from the tail into the lead-in.
 *
 * Coordinates are pixels of a glyph bitmap [width] wide; [alpha] holds one alpha value per pixel;
 * the x-height band runs from row bandTop to row bandBottom (the letter's baseline).
 */
internal object CursiveJoins {

    private const val INK_ALPHA = 40
    private const val CORE_ALPHA = 128
    // Joins of the captured hands turn at most 28 degrees at either end; a sharper one is a hook.
    private const val MAX_TURN = PI / 180 * 40
    // A bridge that turns one way off the tail and back the other way into the lead-in reads as a wave.
    private const val MAX_COUNTER_TURN = PI / 180 * 5
    // A pen width of drawn bridge costs as much as 17 degrees of turn: the writer's own strokes should carry the join.
    private const val LENGTH_COST = 0.3f
    // Moving the next letter a pen width sideways costs a little: of two equal joins, the nearer one wins.
    private const val SHIFT_COST = 0.02f
    const val SECTION_STEP = 0.5f
    // Cross-sections reach 8 px from a stroke's middle line, past the softest captured edge.
    const val SECTION_SIZE = 17

    /**
     * [exitX]/[exitY]: the rightmost ink of the band below its top 30 %, the end of the exit tail.
     * [entryX]/[entryY]: the leftmost ink of the band's lower part, where the pen came down.
     * [bodyLeft]: the leftmost ink at mid x-height. A lead-in reaches well left of it ([hasLeadIn]).
     * [inkAboveLeft]: ink left of [bodyLeft] above mid x-height, an x arm or a z bar. A lead-in crosses
     * the mid band right of bodyLeft, so such a letter's lower-left stroke is its own, not a lead-in.
     */
    data class Tips(
        val exitX: Int,
        val exitY: Int,
        val entryX: Int,
        val entryY: Int,
        val bodyLeft: Int,
        val hasLeadIn: Boolean,
        val inkAboveLeft: Boolean,
    )

    fun tips(alpha: IntArray, width: Int, bandTop: Int, bandBottom: Int): Tips? {
        val height = alpha.size / width
        val top = bandTop.coerceIn(0, height - 1)
        val bottom = bandBottom.coerceIn(top, height - 1)
        val band = bottom - top + 1
        var exitX = -1
        var exitY = -1
        var entryX = Int.MAX_VALUE
        var entryY = -1
        var bodyLeft = Int.MAX_VALUE
        for (y in top..bottom) for (x in 0 until width) {
            if (alpha[y * width + x] <= INK_ALPHA) continue
            // Below the band's top: a j dot or a b bowl up there is not where the pen left.
            if (y >= top + band * 0.3f && x >= exitX) { exitX = x; exitY = y }
            if (y > bottom - band * 0.4f && x <= entryX) { entryX = x; entryY = y }
            if (y - top in (band * 0.3f).toInt()..(band * 0.55f).toInt()) bodyLeft = minOf(bodyLeft, x)
        }
        if (exitX < 0 || entryY < 0 || bodyLeft == Int.MAX_VALUE) return null
        val inkAboveLeft = (top until top + (band * 0.3f).toInt()).any { y -> (0 until bodyLeft).any { x -> alpha[y * width + x] > INK_ALPHA } }
        return Tips(exitX, exitY, entryX, entryY, bodyLeft, hasLeadIn = bodyLeft - entryX > band * 0.15f, inkAboveLeft = inkAboveLeft)
    }

    /**
     * The lead-in stroke as one ink run (row, first x, last x) per row, from the pen-down point at
     * ([entryX], [entryY]) up along the stroke; the rows of the pen-down blob below it come first.
     * Each next run touches the last one. The trace stops where the stroke forks, widens past
     * [maxRunPx] or suddenly (a crossing stroke or a bowl) or ends, or at [bandTop].
     */
    fun leadInRuns(alpha: IntArray, width: Int, entryX: Int, entryY: Int, bandTop: Int, maxRunPx: Float, settleRows: Int = 0): LeadIn {
        val first = runs(alpha, width, entryY).firstOrNull { entryX in it[1]..it[2] } ?: return LeadIn(emptyList(), 0)
        val below = trace(alpha, width, first, step = 1, limit = first[0] + maxRunPx.toInt(), maxRunPx).first
        val (above, ownAbove) = trace(alpha, width, first, step = -1, limit = bandTop, maxRunPx, throughMerge = true, settleRows = settleRows)
        return LeadIn(below.reversed() + first + above, below.size + 1 + ownAbove)
    }

    /**
     * A lead-in traced by [leadInRuns]: its first [ownRuns] runs are its own ink; the rest follow
     * the stroke it merged into (the letter's stem), where it may still be joined but never cut.
     */
    class LeadIn(val runs: List<IntArray>, val ownRuns: Int)

    /**
     * The exit tail as one run per row like [leadInRuns], from its tip back down the stroke. The
     * exit point ([exitX], [exitY]) lies below the band's top part, so the tip may rise above it.
     * Empty when the stroke above the exit point turns back left: that is a bowl's right side (an s), no tail;
     * when it climbs a quarter of the band above [bandTop]: that is a stem, such as a capital's leg; and when its rows
     * are over twice as long as a stroke [penPx] wide at its slant would cross them: that is a blob.
     */
    fun tailRuns(
        alpha: IntArray, width: Int, exitX: Int, exitY: Int, bandTop: Int, bandBottom: Int, maxRunPx: Float, penPx: Float,
    ): List<IntArray> {
        val first = runs(alpha, width, exitY).firstOrNull { exitX in it[1]..it[2] } ?: return emptyList()
        val tip = trace(alpha, width, first, step = -1, limit = 0, maxRunPx)
        if (tip.first.any { it[0] < bandTop - (bandBottom - bandTop) / 4 }) return emptyList()
        // Run centres doubled: 3 means 1.5 px.
        var right = first[1] + first[2]
        for ((_, x0, x1) in tip.first) {
            if (x0 + x1 < right - 3) return emptyList()
            right = maxOf(right, x0 + x1)
        }
        val tail = tip.first.reversed() + first + trace(alpha, width, first, step = 1, limit = bandBottom, maxRunPx).first
        val slant = direction((tail.first()[1] + tail.first()[2]) / 2f to tail.first()[0].toFloat(), (tail.last()[1] + tail.last()[2]) / 2f to tail.last()[0].toFloat())
        val crossing = penPx / maxOf(abs(slant?.second ?: 1f), 0.2f)
        return if (tail.map { it[2] - it[1] + 1 }.sorted()[tail.size / 2] > crossing * 2) emptyList() else tail
    }

    private fun runs(alpha: IntArray, width: Int, y: Int): List<IntArray> = buildList {
        if (y !in 0 until alpha.size / width) return@buildList
        var x = 0
        while (x < width) {
            if (alpha[y * width + x] <= INK_ALPHA) { x++; continue }
            val start = x
            while (x < width && alpha[y * width + x] > INK_ALPHA) x++
            add(intArrayOf(y, start, x - 1))
        }
    }

    /**
     * The runs after [start], one row further in [step] each, up to row [limit], and how many of them
     * come before the stroke meets another one. A run suddenly wider than the stroke so far, or one with a
     * lighter valley between two dark parts, has met another stroke, such as the letter's own downstroke:
     * the trace ends there, or goes on along the merged stroke [throughMerge]. A pointed pen-down start
     * swells over its first [settleRows] rows, which is no merge.
     */
    private fun trace(
        alpha: IntArray, width: Int, start: IntArray, step: Int, limit: Int, maxRunPx: Float,
        throughMerge: Boolean = false, settleRows: Int = 0,
    ): Pair<List<IntArray>, Int> {
        val stroke = mutableListOf(start)
        var own = -1
        while ((stroke.last()[0] + step - limit) * step <= 0) {
            val last = stroke.last()
            val run = runs(alpha, width, last[0] + step).filter { it[1] <= last[2] + 1 && it[2] >= last[1] - 1 }.singleOrNull() ?: break
            val runWidth = run[2] - run[1] + 1
            if (runWidth > maxRunPx) break
            if (own < 0) {
                val widened = stroke.size > settleRows &&
                    runWidth > stroke.drop(settleRows).map { it[2] - it[1] + 1 }.sorted().let { maxOf(it[it.size / 2] * 3 / 2, it[it.size / 2] + 3) }
                if (widened || hasValley(alpha, width, run)) {
                    if (!throughMerge) break
                    own = stroke.size - 1
                }
            }
            stroke += run
        }
        return stroke.drop(1) to if (own < 0) stroke.size - 1 else own
    }

    /** A pixel of the run (row, first x, last x) under half as dark as the darkest ink on both sides of it. */
    private fun hasValley(alpha: IntArray, width: Int, run: IntArray): Boolean {
        val (y, x0, x1) = run
        val row = (x0..x1).map { alpha[y * width + it] }
        val fromRight = row.indices.reversed().runningFold(0) { m, k -> maxOf(m, row[k]) }.reversed()
        var fromLeft = 0
        return row.indices.any { k -> (row[k] * 2 < minOf(fromLeft, fromRight[k + 1])).also { fromLeft = maxOf(fromLeft, row[k]) } }
    }

    /**
     * Where to cut the previous letter's tail and the next letter's lead-in so that a bridge runs on
     * from one into the other: an index into [tail] (stroke centres from the tail's end back) and
     * one into [leadIn] (centres from the pen-down point up), from [firstLeadIn] on. Directions are taken over the
     * [reach] points each stroke keeps past its cut; the lead-in keeps all of them, because past its own ink it
     * turns into the letter, while a tail cut at its root runs on into the letter it leaves. The lead-in may move
     * sideways by any of [shifts] px; [penPx] is the pen width in the same px. Minimises the drawn bridge's length and how far it turns against both strokes,
     * plus a little for each point of stroke cut away and each pixel moved. Null when no bridge up to [maxLength]
     * long turns less than [MAX_TURN] against both and the same way at both ends.
     */
    fun joinCuts(
        tail: List<Pair<Float, Float>>, leadIn: List<Pair<Float, Float>>, firstLeadIn: Int, reach: Int, maxLength: Float = Float.MAX_VALUE,
        shifts: IntProgression = 0..0, penPx: Float = reach.toFloat(),
    ): Cut? {
        fun turn(a: Pair<Float, Float>, b: Pair<Float, Float>) = atan2(a.first * b.second - a.second * b.first, a.first * b.first + a.second * b.second)
        val trimCost = 0.25f / reach
        val tailDirections = tail.indices.map { i -> strokeDirection(tail.subList(i, minOf(i + reach, tail.lastIndex) + 1).reversed()) }
        val leads = firstLeadIn.coerceAtLeast(0)..leadIn.lastIndex - reach
        val leadDirections = leads.associateWith { j -> strokeDirection(leadIn.subList(j, j + reach + 1)) }
        var best: Cut? = null
        var bestCost = Float.MAX_VALUE
        for (s in shifts) for (i in tail.indices) {
            val tailDirection = tailDirections[i] ?: continue
            for (j in leads) {
                val leadDirection = leadDirections[j] ?: continue
                val length = hypot(leadIn[j].first + s - tail[i].first, leadIn[j].second - tail[i].second)
                if (length > maxLength) continue
                val dx = leadIn[j].first + s - tail[i].first
                val dy = leadIn[j].second - tail[i].second
                // Within a pen width along and a quarter pen width across, the strokes overlap as one: the chord's direction
                // is noise, and the bridge turns half the way at each end. Farther across, the step shows.
                val meet = length < penPx && abs(tailDirection.first * dy - tailDirection.second * dx) <= penPx / 4
                val (out, into) = if (meet) turn(tailDirection, leadDirection).let { it / 2 to it / 2 } else {
                    val bridge = direction(tail[i], leadIn[j].first + s to leadIn[j].second) ?: continue
                    turn(tailDirection, bridge) to turn(bridge, leadDirection)
                }
                if (abs(out) >= MAX_TURN || abs(into) >= MAX_TURN) continue
                if (out * into < 0 && minOf(abs(out), abs(into)) >= MAX_COUNTER_TURN) continue
                // The stroke turns no sharper than MAX_TURN per two pen widths, so a short bridge turns little.
                if (abs(out) + abs(into) >= MAX_TURN * maxOf(1f, length / (2 * penPx))) continue
                val cost = LENGTH_COST * length / penPx + abs(out) + abs(into) + trimCost * (i + j - firstLeadIn) + SHIFT_COST * abs(s) / penPx
                if (cost < bestCost) { bestCost = cost; best = Cut(i, j, s) }
            }
        }
        return best
    }

    /** Cut indices into the tail and the lead-in, and how far the next letter moves sideways for them. */
    data class Cut(val tail: Int, val leadIn: Int, val shift: Int = 0)

    /** Unit direction of the line that fits [points] best, from the first towards the last; null when they lie within half a pixel. */
    fun strokeDirection(points: List<Pair<Float, Float>>): Pair<Float, Float>? {
        val chord = direction(points.first(), points.last()) ?: return null
        val mx = points.map { it.first }.average().toFloat()
        val my = points.map { it.second }.average().toFloat()
        val sxx = points.sumOf { ((it.first - mx) * (it.first - mx)).toDouble() }
        val syy = points.sumOf { ((it.second - my) * (it.second - my)).toDouble() }
        val sxy = points.sumOf { ((it.first - mx) * (it.second - my)).toDouble() }
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        val axis = cos(angle).toFloat() to sin(angle).toFloat()
        return if (axis.first * chord.first + axis.second * chord.second < 0) -axis.first to -axis.second else axis
    }

    /** Unit direction from [from] to [to]; null when they are under half a pixel apart. */
    fun direction(from: Pair<Float, Float>, to: Pair<Float, Float>): Pair<Float, Float>? {
        val length = hypot(to.first - from.first, to.second - from.second)
        return if (length < 0.5f) null else (to.first - from.first) / length to (to.second - from.second) / length
    }

    /**
     * How a scanned stroke running in [direction] looks across: its alpha at 0, [SECTION_STEP], 2 [SECTION_STEP] ...
     * px from its middle line, the median over [points] on it (pixel indices; a flat stroke's row-run centres
     * lie across it), each first moved across the stroke to the centre of its ink. Never rises outward, and
     * fades out over the pixel before [maxDistance].
     */
    fun crossSection(
        alpha: IntArray, width: Int, points: List<Pair<Float, Float>>, direction: Pair<Float, Float>, maxDistance: Float = Float.MAX_VALUE,
    ): FloatArray {
        val height = alpha.size / width
        fun at(x: Float, y: Float): Float {
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            fun px(xi: Int, yi: Int) = if (xi in 0 until width && yi in 0 until height) alpha[yi * width + xi].toFloat() else 0f
            val fx = x - x0
            val fy = y - y0
            return (px(x0, y0) * (1 - fx) + px(x0 + 1, y0) * fx) * (1 - fy) + (px(x0, y0 + 1) * (1 - fx) + px(x0 + 1, y0 + 1) * fx) * fy
        }
        val nx = -direction.second
        val ny = direction.first
        val sections = points.map { (px, py) ->
            // The centre of the ink run across the stroke through the point, not of ink beside it.
            fun inkAt(k: Int) = at(px + nx * k * SECTION_STEP, py + ny * k * SECTION_STEP)
            var low = 0
            var high = 0
            if (inkAt(0) > INK_ALPHA) {
                while (low > -SECTION_SIZE && inkAt(low - 1) > INK_ALPHA) low--
                while (high < SECTION_SIZE && inkAt(high + 1) > INK_ALPHA) high++
            }
            val ink = (low..high).map { inkAt(it) }
            val shift = (low..high).sumOf { (it * SECTION_STEP * inkAt(it)).toDouble() }.toFloat() / ink.sum().coerceAtLeast(1f)
            val cx = px + nx * shift
            val cy = py + ny * shift
            FloatArray(SECTION_SIZE) { k -> (at(cx + nx * k * SECTION_STEP, cy + ny * k * SECTION_STEP) + at(cx - nx * k * SECTION_STEP, cy - ny * k * SECTION_STEP)) / 2 }
        }
        // Past the stroke's own edge it stops fading: what lies beyond is paper grain or another stroke.
        return FloatArray(SECTION_SIZE) { k -> sections.map { it[k] }.sorted()[sections.size / 2] }.also { s ->
            for (k in 1 until s.size) s[k] = if (s[k - 1] == 0f || (s[k] >= s[k - 1] && s[k] < s[0] / 2)) 0f else minOf(s[k], s[k - 1])
            for (k in s.indices) s[k] *= (maxDistance - k * SECTION_STEP).coerceIn(0f, 1f)
        }
    }

    /**
     * A single pen stroke is darkest along its middle line; a [section] still within 10 % of that darkness over more
     * than a quarter of [penPx] (at least a pixel) from it was taken where two strokes run side by side.
     */
    fun isSingleStroke(section: FloatArray, penPx: Float): Boolean =
        section.indexOfFirst { it < section[0] * 0.9f }.let { if (it < 0) section.size else it } * SECTION_STEP <= maxOf(1f, penPx / 4)

    /** [section] at [distance] px from the middle line, between its steps. */
    fun sectionAt(section: FloatArray, distance: Float): Float {
        val k = distance / SECTION_STEP
        val i = floor(k).toInt()
        if (i >= section.size - 1) return 0f
        return section[i] + (section[i + 1] - section[i]) * (k - i)
    }

    /**
     * Alpha (0..255) of a bridge along [path] over the [w] x [h] pixels from ([left], [top]): at each pixel centre the
     * cross-section at its distance from the path, running from [fromSection] to [toSection] along it. The ends go
     * [extend] px on into the strokes the bridge joins, back against [startDirection] and on along [endDirection],
     * and are cut off square.
     */
    fun bridgeCoverage(
        path: List<Pair<Float, Float>>, startDirection: Pair<Float, Float>, endDirection: Pair<Float, Float>,
        fromSection: FloatArray, toSection: FloatArray, left: Int, top: Int, w: Int, h: Int, extend: Float,
    ): FloatArray {
        val points = listOf(path[0].first - startDirection.first * extend to path[0].second - startDirection.second * extend) + path +
            listOf(path.last().first + endDirection.first * extend to path.last().second + endDirection.second * extend)
        val along = FloatArray(points.size).also { a ->
            for (k in 1 until points.size) a[k] = a[k - 1] + hypot(points[k].first - points[k - 1].first, points[k].second - points[k - 1].second)
        }
        val length = along.last() - 2 * extend
        return FloatArray(w * h) { i ->
            val qx = left + i % w + 0.5f
            val qy = top + i / w + 0.5f
            var best = Float.MAX_VALUE
            var bestAlong = 0f
            var outside = false
            for (k in 0 until points.size - 1) {
                val (ax, ay) = points[k]
                val dx = points[k + 1].first - ax
                val dy = points[k + 1].second - ay
                val squared = dx * dx + dy * dy
                if (squared == 0f) continue
                val u = ((qx - ax) * dx + (qy - ay) * dy) / squared
                val v = u.coerceIn(0f, 1f)
                val d = hypot(ax + dx * v - qx, ay + dy * v - qy)
                if (d < best) {
                    best = d
                    bestAlong = along[k] + (along[k + 1] - along[k]) * v
                    outside = (k == 0 && u < 0f) || (k == points.size - 2 && u > 1f)
                }
            }
            if (outside) return@FloatArray 0f
            val t = ((bestAlong - extend) / length).coerceIn(0f, 1f)
            sectionAt(fromSection, best) * (1 - t) + sectionAt(toSection, best) * t
        }
    }

    /** Pen width of the strokes in [alpha]: twice the core area over its boundary length. */
    fun penWidth(alpha: IntArray, width: Int): Float {
        val height = alpha.size / width
        fun core(x: Int, y: Int) = x in 0 until width && y in 0 until height && alpha[y * width + x] > CORE_ALPHA
        var area = 0
        var boundary = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (!core(x, y)) continue
            area++
            if (!core(x - 1, y) || !core(x + 1, y) || !core(x, y - 1) || !core(x, y + 1)) boundary++
        }
        return if (boundary == 0) 0f else 2f * area / boundary
    }

    /** Most of the hand's x-height letters start with a lead-in from the baseline. */
    fun isCursiveHand(leadIns: List<Boolean>): Boolean = leadIns.size >= 4 && leadIns.count { it } * 3 >= leadIns.size * 2

    /**
     * A letter joins the lowercase letter after it; b, o, v and w (and their capitals) leave from the x-height,
     * where no lead-in starts. Whether a capital's exit is a tail at all, the tail and bridge geometry decides.
     */
    fun joins(previous: Char, next: Char): Boolean =
        previous.isLetter() && next.isLowerCase() && previous.lowercaseChar() !in "bovw"
}
