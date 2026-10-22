package com.baer.handtype.feature.template

import kotlin.math.roundToInt

/** Text size of a rendered note as a factor of the default line height. */
object TextSizeScale {
    const val MIN = 0.7f
    const val MAX = 1.5f
    const val DEFAULT = 1f

    fun lineHeightPx(basePx: Int, scale: Float): Int = (basePx * scale.coerceIn(MIN, MAX)).roundToInt()
}
