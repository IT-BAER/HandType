package com.baer.handtype.template

import android.graphics.Bitmap

/** Builds œ æ Œ Æ a hand has no glyph for from the writer's own two letters, set close enough to touch. */
internal object LigatureComposer {

    private const val INK_ALPHA = 40

    private val PARTS = mapOf('œ' to ('o' to 'e'), 'æ' to ('a' to 'e'), 'Œ' to ('O' to 'E'), 'Æ' to ('A' to 'E'))

    /** The two letters of [c], or null when [c] is no ligature. */
    fun parts(c: Char): Pair<Char, Char>? = PARTS[c]

    /**
     * [first] and [second], glyph canvases of one height sharing a baseline, side by side: on the rows where both
     * have ink, the closest strokes overlap by [overlapPx] columns. Where both have ink, the darker pixel stays.
     */
    fun join(first: Bitmap, second: Bitmap, overlapPx: Int): Bitmap {
        require(first.height == second.height) { "canvas heights ${first.height} and ${second.height}" }
        val h = first.height
        val a = IntArray(first.width * h).also { first.getPixels(it, 0, first.width, 0, 0, first.width, h) }
        val b = IntArray(second.width * h).also { second.getPixels(it, 0, second.width, 0, 0, second.width, h) }
        val right = IntArray(h) { y -> (first.width - 1 downTo 0).firstOrNull { x -> a[y * first.width + x] ushr 24 > INK_ALPHA } ?: -1 }
        val left = IntArray(h) { y -> (0 until second.width).firstOrNull { x -> b[y * second.width + x] ushr 24 > INK_ALPHA } ?: -1 }
        val shared = (0 until h).filter { right[it] >= 0 && left[it] >= 0 }
        // Offset of the second canvas: 1 - overlap columns from the first letter's ink to the second's.
        val dx = if (shared.isNotEmpty()) {
            1 - overlapPx - shared.minOf { left[it] - right[it] }
        } else {
            1 - overlapPx + (right.maxOrNull() ?: -1) - (left.filter { it >= 0 }.minOrNull() ?: 0)
        }
        val pad = maxOf(0, -dx)
        val width = maxOf(first.width + pad, dx + pad + second.width)
        val out = IntArray(width * h)
        for (y in 0 until h) {
            for (x in 0 until first.width) out[y * width + x + pad] = a[y * first.width + x]
            for (x in 0 until second.width) {
                val i = y * width + x + dx + pad
                val p = b[y * second.width + x]
                if (p ushr 24 > out[i] ushr 24) out[i] = p
            }
        }
        return Bitmap.createBitmap(out, width, h, Bitmap.Config.ARGB_8888)
    }
}
