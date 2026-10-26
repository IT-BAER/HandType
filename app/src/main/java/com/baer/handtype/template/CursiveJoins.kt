package com.baer.handtype.template

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot

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
     * exit point ([exitX], [exitY]) lies below the band's top, so the tip may rise above it.
     */
    fun tailRuns(alpha: IntArray, width: Int, exitX: Int, exitY: Int, bandBottom: Int, maxRunPx: Float): List<IntArray> {
        val first = runs(alpha, width, exitY).firstOrNull { exitX in it[1]..it[2] } ?: return emptyList()
        val tip = trace(alpha, width, first, step = -1, limit = 0, maxRunPx)
        return tip.first.reversed() + first + trace(alpha, width, first, step = 1, limit = bandBottom, maxRunPx).first
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
     * come before the stroke meets another one. A run suddenly wider than the stroke so far has met
     * another stroke, such as the letter's own downstroke: the trace ends there, or goes on along the
     * merged stroke [throughMerge]. A pointed pen-down start swells over its first [settleRows] rows,
     * which is no merge.
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
            if (own < 0 && stroke.size > settleRows) {
                val usual = stroke.drop(settleRows).map { it[2] - it[1] + 1 }.sorted().let { it[it.size / 2] }
                if (runWidth > maxOf(usual * 3 / 2, usual + 3)) {
                    if (!throughMerge) break
                    own = stroke.size - 1
                }
            }
            stroke += run
        }
        return stroke.drop(1) to if (own < 0) stroke.size - 1 else own
    }

    /**
     * Where to cut the previous letter's tail and the next letter's lead-in so that a bridge runs on
     * from one into the other: an index into [tail] (stroke centres from the tail's end back) and
     * one into [leadIn] (centres from the pen-down point up), from [firstLeadIn] on. Directions are
     * taken over the [reach] points each stroke keeps past its cut. Minimises how far the bridge turns against both strokes, plus a
     * little for each point of stroke cut away. Null when no lead-in point lies ahead of the tail.
     */
    fun joinCuts(tail: List<Pair<Float, Float>>, leadIn: List<Pair<Float, Float>>, firstLeadIn: Int, reach: Int): Pair<Int, Int>? {
        fun turn(a: Pair<Float, Float>, b: Pair<Float, Float>) = acos((a.first * b.first + a.second * b.second).coerceIn(-1f, 1f))
        val trimCost = 0.25f / reach
        var best: Pair<Int, Int>? = null
        var bestCost = Float.MAX_VALUE
        for (i in tail.indices) {
            val tailDirection = direction(tail[minOf(i + reach, tail.lastIndex)], tail[i]) ?: continue
            for (j in firstLeadIn.coerceAtLeast(0)..leadIn.lastIndex) {
                val leadDirection = direction(leadIn[j], leadIn[minOf(j + reach, leadIn.lastIndex)]) ?: continue
                val bridge = direction(tail[i], leadIn[j]) ?: continue
                if (turn(tailDirection, bridge) >= PI / 2 || turn(bridge, leadDirection) >= PI / 2) continue
                val cost = turn(tailDirection, bridge) + turn(bridge, leadDirection) + trimCost * (i + j - firstLeadIn)
                if (cost < bestCost) { bestCost = cost; best = i to j }
            }
        }
        return best
    }

    /** Unit direction from [from] to [to]; null when they are under half a pixel apart. */
    fun direction(from: Pair<Float, Float>, to: Pair<Float, Float>): Pair<Float, Float>? {
        val length = hypot(to.first - from.first, to.second - from.second)
        return if (length < 0.5f) null else (to.first - from.first) / length to (to.second - from.second) / length
    }

    /**
     * How a scanned stroke along [runs] looks: the median of each run's darkest alpha, and the
     * median count of its pixels at least half that dark (its width along the row). A drawn stroke
     * with that alpha and width carries about as much ink as the soft-edged scan.
     */
    fun strokeProfile(alpha: IntArray, width: Int, runs: List<IntArray>): Pair<Int, Float> {
        val rows = runs.map { (y, x0, x1) -> (x0..x1).map { alpha[y * width + it] } }
        val core = rows.map { it.max() }.sorted()[rows.size / 2]
        val half = rows.map { row -> row.count { it * 2 >= row.max() } }.sorted()[rows.size / 2]
        return core to half.toFloat()
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

    /** Lowercase letters join; b, o, v and w leave from the x-height, where no lead-in starts. */
    fun joins(previous: Char, next: Char): Boolean =
        previous.isLowerCase() && next.isLowerCase() && previous !in "bovw"
}
