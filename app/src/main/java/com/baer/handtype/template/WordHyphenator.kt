package com.baer.handtype.template

/** Splits a word that is wider than one line into "abc-" pieces that each fit. */
internal object WordHyphenator {

    private const val MIN_CHARS_BEFORE_HYPHEN = 2
    private const val MIN_CHARS_AFTER_HYPHEN = 3

    /** [fits] answers whether a piece (with its hyphen) fits on one line. */
    fun pieces(word: String, fits: (String) -> Boolean): List<String> {
        val pieces = mutableListOf<String>()
        var rest = word
        while (true) {
            // Trailing punctuation does not count towards the letters carried to the next line.
            val minAfter = MIN_CHARS_AFTER_HYPHEN + rest.length - rest.trimEnd { !it.isLetterOrDigit() }.length
            if (fits(rest) || rest.length < MIN_CHARS_BEFORE_HYPHEN + minAfter) break
            var cut = rest.length - minAfter
            while (cut > MIN_CHARS_BEFORE_HYPHEN && !fits(rest.take(cut) + "-")) cut--
            cut = (cut downTo MIN_CHARS_BEFORE_HYPHEN).firstOrNull { isSyllableStart(rest, it) } ?: cut
            pieces += rest.take(cut) + "-"
            rest = rest.drop(cut)
        }
        return pieces + rest
    }

    private const val VOWELS = "aeiouyäöüàáâãåæèéêëìíîïòóôõøœùúûýаеёиоуыэюя"
    private val consonantUnits = listOf("sch", "ch", "ck", "ph", "th")

    private fun isVowel(c: Char) = c.lowercaseChar() in VOWELS

    /**
     * Heuristic, no dictionary: a syllable starts at one consonant (or sch/ch/ck/ph/th)
     * followed by a vowel, so "Öster-reich", "Mäd-chen", "Zu-cker". Compound seams are not known.
     */
    private fun isSyllableStart(word: String, index: Int): Boolean {
        val prev = word.getOrNull(index - 1) ?: return false
        val c = word.getOrNull(index) ?: return false
        if (!prev.isLetter() || !c.isLetter() || isVowel(c)) return false
        fun unitAt(unit: String, at: Int) = word.startsWith(unit, at, ignoreCase = true)
        // Never cut inside a consonant unit.
        if (consonantUnits.any { unit -> (1 until unit.length).any { unitAt(unit, index - it) } }) return false
        val unitLength = consonantUnits.firstOrNull { unitAt(it, index) }?.length ?: 1
        val next = word.getOrNull(index + unitLength) ?: return false
        return isVowel(next) && word.take(index).any(::isVowel)
    }
}
