package com.baer.handtype.feature.capture

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeSheetGeneratorTest {

    private val base = ('A'..'Z') + ('a'..'z') + ('0'..'9')

    @Test
    fun germanSheetWritesUmlautsAndEszettInTheLastRow() {
        val cells = PracticeSheetGenerator.cellCharacters(PracticeSheetGenerator.localeCharacters(Locale.GERMANY))

        assertEquals(72, cells.size)
        assertEquals(base, cells.take(62))
        assertEquals("ÄÖÜäöüß".toList(), cells.subList(62, 69))
        assertTrue(cells.drop(69).all { it == ' ' })
    }

    @Test
    fun languagesWithoutExtrasLeaveTheLastRowBlank() {
        for (locale in listOf(Locale.ENGLISH, Locale.FRENCH, Locale.forLanguageTag("pt-BR"), Locale.JAPANESE)) {
            val cells = PracticeSheetGenerator.cellCharacters(PracticeSheetGenerator.localeCharacters(locale))
            assertEquals("$locale", 72, cells.size)
            assertTrue("$locale", cells.drop(62).all { it == ' ' })
        }
    }

    @Test
    fun legacySheetKeepsTheEightRowLayout() {
        val cells = PracticeSheetGenerator.cellCharacters(
            "ÄÖÜäöüß".toList(),
            rows = PracticeSheetGenerator.LEGACY_GRID_ROWS,
        )

        assertEquals(base + listOf(' ', ' '), cells)
    }

    @Test
    fun cellsSplitTheGridIntoEqualRows() {
        for (rows in listOf(PracticeSheetGenerator.LEGACY_GRID_ROWS, PracticeSheetGenerator.GRID_ROWS)) {
            val cells = PracticeSheetGenerator.normalizedCells(PracticeSheetGenerator.cellCharacters(rows = rows))
            val tops = cells.map { it.top }.distinct()
            assertEquals(rows, tops.size)
            assertEquals(0.94f, cells.last().bottom, 1e-4f)
            val heights = cells.map { it.bottom - it.top }
            assertTrue(heights.all { kotlin.math.abs(it - heights.first()) < 1e-4f })
        }
    }
}
