package com.baer.handtype.template

import org.junit.Assert.assertEquals
import org.junit.Test

class WordHyphenatorTest {

    private fun maxChars(n: Int): (String) -> Boolean = { it.length <= n }

    @Test
    fun wordThatFitsStaysWhole() {
        assertEquals(listOf("Haus"), WordHyphenator.pieces("Haus", maxChars(8)))
    }

    @Test
    fun cutsAtSyllableBeforeConsonantAndVowel() {
        assertEquals(listOf("Öster-", "reich!"), WordHyphenator.pieces("Österreich!", maxChars(8)))
    }

    @Test
    fun keepsChAndCkTogether() {
        assertEquals(listOf("Mäd-", "chen"), WordHyphenator.pieces("Mädchen", maxChars(6)))
        assertEquals(listOf("Zu-", "cker"), WordHyphenator.pieces("Zucker", maxChars(5)))
    }

    @Test
    fun keepsSchTogether() {
        assertEquals(listOf("Ta-", "sche"), WordHyphenator.pieces("Tasche", maxChars(5)))
    }

    @Test
    fun longWordSplitsIntoSeveralSyllablePieces() {
        assertEquals(
            listOf("Donau-", "dampf-", "schiff-", "fahrt"),
            WordHyphenator.pieces("Donaudampfschifffahrt", maxChars(8)),
        )
    }

    @Test
    fun wordWithoutSyllableFallsBackToGreedyCut() {
        assertEquals(listOf("xxxxx-", "xxxxx"), WordHyphenator.pieces("xxxxxxxxxx", maxChars(6)))
    }

    @Test
    fun keepsAtLeastTwoLettersBeforeAndThreeAfterTheHyphen() {
        // "Ab-" is the shortest head; "ent" the shortest tail.
        assertEquals(listOf("Ab-", "ent"), WordHyphenator.pieces("Abent", maxChars(4)))
        // Too short to split at all.
        assertEquals(listOf("Hand"), WordHyphenator.pieces("Hand", maxChars(2)))
    }
}
