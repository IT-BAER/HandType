package com.baer.handtype.feature.capture.stylus

/**
 * Paging, grid sizing and per-cell checking for the stylus writing grid. A page holds the sheet
 * characters `start until pageEnd(...)`; character indices are absolute positions in the sheet.
 */
object StylusPages {
    const val COLUMNS = 3
    const val MAX_ROWS = 3
    const val MIN_ROWS = 2
    const val GAP_DP = 8f
    const val MIN_CELL_HEIGHT_DP = 88f

    data class Grid(val rows: Int, val cellHeightDp: Float) {
        val pageSize: Int get() = COLUMNS * rows
    }

    /** [results] holds only the cells that were checked; [recognizerUnavailable] is null when none reached recognition. */
    data class PageCheck(val results: Map<Int, PrecheckResult>, val recognizerUnavailable: Boolean?)

    /** [failing] is sorted; [armed] failed recognition only and is kept by the next check. */
    data class Review(val failing: List<Int>, val armed: Set<Int>, val passed: Set<Int>)

    /**
     * [COLUMNS] x [MAX_ROWS] cells of [aspectRatio] (width / height) fitted into the area; when the
     * cell height would drop below [MIN_CELL_HEIGHT_DP], [MIN_ROWS] rows instead.
     */
    fun grid(widthDp: Float, heightDp: Float, aspectRatio: Float): Grid {
        val byWidth = (widthDp - GAP_DP * (COLUMNS - 1)) / COLUMNS / aspectRatio
        fun fit(rows: Int) = minOf(byWidth, (heightDp - GAP_DP * (rows - 1)) / rows).coerceAtLeast(0f)
        val full = fit(MAX_ROWS)
        return if (full >= MIN_CELL_HEIGHT_DP) Grid(MAX_ROWS, full) else Grid(MIN_ROWS, fit(MIN_ROWS))
    }

    fun pageEnd(start: Int, pageSize: Int, total: Int): Int = minOf(start + pageSize, total)

    fun isLastPage(start: Int, pageSize: Int, total: Int): Boolean = start + pageSize >= total

    fun previousStart(start: Int, pageSize: Int): Int = (start - pageSize).coerceAtLeast(0)

    fun pageStarts(total: Int, pageSize: Int): List<Int> = (0 until total step pageSize).toList()

    fun row(slot: Int): Int = slot / COLUMNS

    fun column(slot: Int): Int = slot % COLUMNS

    fun slot(row: Int, column: Int): Int = row * COLUMNS + column

    /**
     * The cell on `start until end` written in last: the newest [order] entry that still has
     * strokes, else (order lost after process death) the last non-empty cell.
     */
    fun lastWritten(order: List<Int>, start: Int, end: Int, strokeCount: (Int) -> Int): Int? {
        for (k in order.indices.reversed()) {
            val i = order[k]
            if (i in start until end && strokeCount(i) > 0) return i
        }
        return (end - 1 downTo start).firstOrNull { strokeCount(it) > 0 }
    }

    /**
     * Shape check, then recognition, for every cell on `start until end` except [verified] ones.
     * An [armed] cell that still passes the shape check is kept without recognition. Recognition
     * runs one cell at a time in cell order.
     */
    suspend fun checkPage(
        characters: List<Char>,
        glyphs: List<List<InkStroke>>,
        start: Int,
        end: Int,
        verified: Set<Int>,
        armed: Set<Int>,
        recognize: suspend (List<InkStroke>) -> List<String>?,
    ): PageCheck {
        val results = LinkedHashMap<Int, PrecheckResult>()
        var unavailable: Boolean? = null
        for (i in start until end) {
            if (i in verified) continue
            val strokes = glyphs[i]
            val shape = LetterPrecheck.shapeCheck(characters[i], strokes)
            results[i] = when {
                shape != PrecheckResult.Pass -> shape
                i in armed -> PrecheckResult.Pass
                else -> {
                    val candidates = recognize(strokes)
                    unavailable = candidates == null
                    LetterPrecheck.evaluate(characters[i], strokes, candidates)
                }
            }
        }
        return PageCheck(results, unavailable)
    }

    fun review(results: Map<Int, PrecheckResult>): Review = Review(
        failing = results.filterValues { it != PrecheckResult.Pass }.keys.sorted(),
        armed = results.filterValues { it is PrecheckResult.WrongLetter }.keys,
        passed = results.filterValues { it == PrecheckResult.Pass }.keys,
    )
}
