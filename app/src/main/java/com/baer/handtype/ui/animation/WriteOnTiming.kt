package com.baer.handtype.ui.animation

import androidx.compose.animation.core.Easing

/** GSAP `power2.out`, the promo's entrance curve. */
val EaseOutCubic = Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) }

private fun easeInOutQuad(t: Float): Float =
    if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f

/** Result note write-on: each text line is wiped in left to right, one after another. */
object NoteWriteOn {
    const val START_DELAY_MILLIS = 175
    const val LINE_MILLIS = 250
    const val LINE_STRIDE_MILLIS = 260
    const val MAX_TOTAL_MILLIS = 2800

    /** Promo pacing up to 10 lines; longer notes start lines closer together to stay in the cap. */
    fun strideMillis(lineCount: Int): Int {
        if (lineCount <= 1) return LINE_STRIDE_MILLIS
        return minOf(LINE_STRIDE_MILLIS, (MAX_TOTAL_MILLIS - START_DELAY_MILLIS - LINE_MILLIS) / (lineCount - 1))
    }

    fun totalMillis(lineCount: Int): Int =
        START_DELAY_MILLIS + strideMillis(lineCount) * (lineCount - 1).coerceAtLeast(0) + LINE_MILLIS

    /** Written fraction (0..1) of line [index] at [elapsedMillis] after the result appears. */
    fun lineProgress(index: Int, lineCount: Int, elapsedMillis: Long): Float {
        val start = START_DELAY_MILLIS + strideMillis(lineCount).toLong() * index
        return ((elapsedMillis - start).toFloat() / LINE_MILLIS).coerceIn(0f, 1f)
    }
}

/**
 * Pen trace of the Generating card: the promo pass reaches 82% of the path after 1190 ms and the
 * result replaces the card at 1090 ms. A slower render finishes the path, then loops full passes.
 */
object PenPass {
    const val RESULT_AT_MILLIS = 1090
    const val PASS_MILLIS = 1190
    const val PASS_END = 0.82f
    const val FINISH_MILLIS = 400
    const val LOOP_MILLIS = 1400

    fun progress(elapsedMillis: Long): Float {
        val t = elapsedMillis.coerceAtLeast(0)
        val finishEnd = PASS_MILLIS + FINISH_MILLIS
        return when {
            t <= PASS_MILLIS -> PASS_END * easeInOutQuad(t.toFloat() / PASS_MILLIS)
            t <= finishEnd -> PASS_END + (1f - PASS_END) * easeInOutQuad((t - PASS_MILLIS).toFloat() / FINISH_MILLIS)
            else -> easeInOutQuad(((t - finishEnd) % LOOP_MILLIS).toFloat() / LOOP_MILLIS)
        }
    }
}
