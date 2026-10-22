package com.baer.handtype.template

import com.baer.handtype.template.AccentComposer.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccentComposerTest {

    @Test
    fun accentedLettersSplitIntoBaseLetterAndMarks() {
        val expected = mapOf(
            'é' to AccentComposer.Decomposition('e', listOf(Mark.ACUTE)),
            'è' to AccentComposer.Decomposition('e', listOf(Mark.GRAVE)),
            'ê' to AccentComposer.Decomposition('e', listOf(Mark.CIRCUMFLEX)),
            'ë' to AccentComposer.Decomposition('e', listOf(Mark.DIAERESIS)),
            'Ñ' to AccentComposer.Decomposition('N', listOf(Mark.TILDE)),
            'ç' to AccentComposer.Decomposition('c', listOf(Mark.CEDILLA)),
            'Å' to AccentComposer.Decomposition('A', listOf(Mark.RING)),
            'í' to AccentComposer.Decomposition('i', listOf(Mark.ACUTE)),
            // Marks come out in NFD order, nearest to the letter first.
            'ǘ' to AccentComposer.Decomposition('u', listOf(Mark.DIAERESIS, Mark.ACUTE)),
        )
        for ((character, decomposition) in expected) {
            assertEquals("$character", decomposition, AccentComposer.decompose(character))
        }
    }

    @Test
    fun charactersWithoutSupportedMarksDoNotDecompose() {
        // ß and œ have no NFD split, ǒ carries a caron, and plain letters have no mark at all.
        for (character in listOf('ß', 'œ', 'ǒ', 'a', 'Z', '1', '?', ' ')) {
            assertNull("$character", AccentComposer.decompose(character))
        }
    }
}
