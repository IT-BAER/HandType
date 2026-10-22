package com.baer.handtype.feature.capture.stylus

import com.baer.handtype.feature.capture.PracticeSheetGenerator

sealed interface PrecheckResult {
    data object Pass : PrecheckResult
    data object Empty : PrecheckResult
    data object OutsideBox : PrecheckResult
    data object TooSmall : PrecheckResult
    data object TooLarge : PrecheckResult

    /** A capital, digit or ascender letter that stays below the x-height guide. */
    data object ReachUp : PrecheckResult

    /** [seen] is the recognizer's top candidate, or null when it returned none. */
    data class WrongLetter(val seen: String?) : PrecheckResult
}

/**
 * Checks one written character before it is accepted. Shape checks use the same guide ratios as
 * the printed cell; the recognition check runs only when candidates are available.
 */
object LetterPrecheck {

    private const val UPPER = PracticeSheetGenerator.GUIDE_UPPER_RATIO
    private const val BASELINE = PracticeSheetGenerator.GUIDE_BASELINE_RATIO
    private const val GUIDE_GAP = BASELINE - UPPER
    private const val MIN_HEIGHT = GUIDE_GAP * 0.5f
    private const val MAX_HEIGHT = GUIDE_GAP * 2f
    private const val TOP_CANDIDATES = 5

    private const val ASCENDER_LOWERCASE = "bdfhklß"

    // Characters whose written shapes cannot be told apart by case or digit form.
    private val lookAlikeGroups: List<Set<Char>> = "cosuvwxz".map { setOf(it, it.uppercaseChar()) } +
        listOf(setOf('0', 'O'), setOf('1', 'l', 'I'))

    /**
     * Shape checks first; [candidates] null means recognition is unavailable and is skipped. The
     * en-US recognizer cannot confirm letters outside ASCII, so those get the shape checks only.
     */
    fun evaluate(expected: Char, strokes: List<InkStroke>, candidates: List<String>?): PrecheckResult {
        val shape = shapeCheck(expected, strokes)
        if (shape != PrecheckResult.Pass || candidates == null || expected.code > 0x7F) return shape
        return if (candidates.take(TOP_CANDIDATES).any { matches(expected, it) }) {
            PrecheckResult.Pass
        } else {
            PrecheckResult.WrongLetter(candidates.firstOrNull()?.trim())
        }
    }

    fun shapeCheck(expected: Char, strokes: List<InkStroke>): PrecheckResult {
        val points = strokes.flatMap { it.points }
        if (points.isEmpty()) return PrecheckResult.Empty
        if (points.any { it.x < 0f || it.x > 1f || it.y < 0f || it.y > 1f }) return PrecheckResult.OutsideBox
        val top = points.minOf { it.y }
        val height = points.maxOf { it.y } - top
        return when {
            height > MAX_HEIGHT -> PrecheckResult.TooLarge
            height < MIN_HEIGHT -> PrecheckResult.TooSmall
            reachesUp(expected) && top >= UPPER -> PrecheckResult.ReachUp
            else -> PrecheckResult.Pass
        }
    }

    fun matches(expected: Char, candidate: String): Boolean {
        val text = candidate.trim()
        if (text.length != 1) return false
        val seen = text[0]
        if (seen == expected) return true
        return lookAlikeGroups.any { expected in it && seen in it }
    }

    private fun reachesUp(c: Char): Boolean = c.isUpperCase() || c.isDigit() || c in ASCENDER_LOWERCASE
}
