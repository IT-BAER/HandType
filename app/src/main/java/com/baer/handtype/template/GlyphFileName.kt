package com.baer.handtype.template

import java.util.Locale

/** Glyph PNG names `u<HEX>_v<variant>.png`, shared by the bundled assets and user templates. */
internal object GlyphFileName {

    // Locale.ROOT: %d in the default locale writes e.g. Arabic-Indic digits, and the asset lookup fails.
    fun of(character: Char, variant: Int): String =
        String.format(Locale.ROOT, "u%04X_v%d.png", character.code, variant)

    /** The character a glyph file holds, or null for any other file. */
    fun charOf(fileName: String): Char? {
        val match = VARIANT_REGEX.matchEntire(fileName) ?: return null
        val codePoint = match.groupValues[1].toIntOrNull(16) ?: return null
        return codePoint.toChar()
    }

    // \p{Nd}: builds before Locale.ROOT saved user glyphs with the device's digits, e.g. "u0041_v٠.png".
    private val VARIANT_REGEX = Regex("u([0-9A-Fa-f]+)_v\\p{Nd}+\\.png")
}
