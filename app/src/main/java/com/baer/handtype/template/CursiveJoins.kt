package com.baer.handtype.template

/**
 * Geometry for joining the letters of a cursive hand. Each letter was written alone on the sheet,
 * so it starts with a lead-in from the baseline, often with a pen-down blob, and ends with an exit
 * tail rising to the right. Set side by side, the blob sits under the previous tail like a dot.
 * Inside a word the previous tail takes the lead-in's place: the renderer drops the lead-in and,
 * where the tail ends short of the letter body, draws a connector to the nearest ink of the body.
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
     */
    data class Tips(
        val exitX: Int,
        val exitY: Int,
        val entryX: Int,
        val entryY: Int,
        val bodyLeft: Int,
        val hasLeadIn: Boolean,
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
        return Tips(exitX, exitY, entryX, entryY, bodyLeft, hasLeadIn = bodyLeft - entryX > band * 0.15f)
    }

    /** Pixel indices of the lead-in: band ink left of the body. */
    fun leadInPixels(alpha: IntArray, width: Int, bandTop: Int, bandBottom: Int, bodyLeft: Int): List<Int> {
        val height = alpha.size / width
        return (bandTop.coerceAtLeast(0)..bandBottom.coerceAtMost(height - 1)).flatMap { y ->
            (0 until minOf(bodyLeft, width)).map { y * width + it }.filter { alpha[it] > INK_ALPHA }
        }
    }

    /** Where the lead-in in [leadIn] (pixel indices) meets the body: its rightmost pixel, the top one on a tie. */
    fun leadInTop(leadIn: List<Int>, width: Int): Int? =
        leadIn.maxWithOrNull(compareBy<Int> { it % width }.thenByDescending { it / width })

    /**
     * Pixel indices of the body's front, where a connector may end: band ink from [bodyLeft] to a
     * third of the band further right.
     */
    fun bodyFront(alpha: IntArray, width: Int, bandTop: Int, bandBottom: Int, bodyLeft: Int): List<Int> {
        val height = alpha.size / width
        val right = minOf(width, bodyLeft + (bandBottom - bandTop + 1) / 3)
        return (bandTop.coerceAtLeast(0)..bandBottom.coerceAtMost(height - 1)).flatMap { y ->
            (bodyLeft.coerceAtLeast(0) until right).map { y * width + it }.filter { alpha[it] > INK_ALPHA }
        }
    }

    /** Median alpha of the stroke cores in [alpha]: the darkness a drawn stroke of this hand needs. */
    fun inkAlpha(alpha: IntArray): Int = alpha.filter { it > CORE_ALPHA }.sorted().let { it.getOrElse(it.size / 2) { 255 } }

    /**
     * Unit direction the exit tail runs in at its end ([exitX], [exitY]): from the centre of the
     * ink within [radius] toward the end. A connector leaves along it, so the stroke flows on.
     */
    fun tailDirection(alpha: IntArray, width: Int, exitX: Int, exitY: Int, radius: Float): Pair<Float, Float> {
        val height = alpha.size / width
        val r = radius.toInt()
        var sx = 0f
        var sy = 0f
        var n = 0
        for (y in exitY - r..exitY + r) for (x in exitX - r..exitX + r) {
            if (x !in 0 until width || y !in 0 until height || alpha[y * width + x] <= INK_ALPHA) continue
            if ((x - exitX) * (x - exitX) + (y - exitY) * (y - exitY) > radius * radius) continue
            sx += x; sy += y; n++
        }
        val dx = if (n == 0) 1f else exitX - sx / n
        val dy = if (n == 0) 0f else exitY - sy / n
        val length = kotlin.math.hypot(dx, dy).takeIf { it > 0f } ?: return 1f to 0f
        return dx / length to dy / length
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
