package com.baer.handtype.ui.animation

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.ui.geometry.Rect
import com.baer.handtype.template.InkRow

/** GSAP `power2.out`, the promo's entrance curve. */
val EaseOutCubic = Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) }

private fun easeInOutQuad(t: Float): Float =
    if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f

/** Into Generating the old screen stays fully visible under the incoming layer, then goes. */
object PhaseFade {
    const val GENERATING_IN_MILLIS = 180
    // A zero-length tween ends at once and skips its delay; snap waits for it.
    val holdOldLayer: FiniteAnimationSpec<Float> = snap(delayMillis = GENERATING_IN_MILLIS)
}

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

    /**
     * Written boxes in bitmap pixels: each line's band up to its wipe, plus the line's ink that
     * reaches into another band outside that band's span, so no ink waits for the end.
     */
    fun revealBoxes(rows: List<InkRow>, elapsedMillis: Long): List<Rect> = buildList {
        rows.forEachIndexed { i, row ->
            val p = lineProgress(i, rows.size, elapsedMillis)
            if (p <= 0f) return@forEachIndexed
            val wipeX = row.left + (row.right - row.left) * p
            add(Rect(row.left.toFloat(), row.top.toFloat(), wipeX, row.bottom.toFloat()))
            rows.forEachIndexed { k, band ->
                val top = maxOf(band.top, row.inkTop).toFloat()
                val bottom = minOf(band.bottom, row.inkBottom).toFloat()
                if (k == i || top >= bottom) return@forEachIndexed
                val leftEnd = minOf(wipeX, band.left.toFloat())
                if (leftEnd > row.left) add(Rect(row.left.toFloat(), top, leftEnd, bottom))
                val rightStart = maxOf(row.left, band.right).toFloat()
                if (wipeX > rightStart) add(Rect(rightStart, top, wipeX, bottom))
            }
        }
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
