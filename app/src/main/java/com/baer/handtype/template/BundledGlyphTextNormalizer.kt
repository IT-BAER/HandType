package com.baer.handtype.template

internal object BundledGlyphTextNormalizer {

    private val replacements = mapOf(
        '\u00A0' to " ",
        '\u1680' to " ",
        '\u2000' to " ",
        '\u2001' to " ",
        '\u2002' to " ",
        '\u2003' to " ",
        '\u2004' to " ",
        '\u2005' to " ",
        '\u2006' to " ",
        '\u2007' to " ",
        '\u2008' to " ",
        '\u2009' to " ",
        '\u200A' to " ",
        '\u202F' to " ",
        '\u205F' to " ",
        '\u3000' to " ",
        '\u060C' to ",",
        '\u061F' to "?",
        '\u2010' to "-",
        '\u2011' to "-",
        '\u2012' to "-",
        '\u2013' to "-",
        '\u2014' to "-",
        '\u2015' to "-",
        '\u2018' to "'",
        '\u2019' to "'",
        '\u201A' to "'",
        '\u201B' to "'",
        '\u201C' to "''",
        '\u201D' to "''",
        '\u201E' to "''",
        '\u201F' to "''",
        '\u2026' to "...",
        '\u2032' to "'",
        '\u2033' to "''",
        '\u2035' to "'",
        '\u2036' to "''",
        '\u2212' to "-",
        '\u3001' to ",",
        '\u3002' to ".",
        '\uFF01' to "!",
        '\uFF07' to "'",
        '\uFF0C' to ",",
        '\uFF0D' to "-",
        '\uFF0E' to ".",
        '\uFF1F' to "?",
    )

    fun normalize(text: String): String {
        val unifiedLineEndings = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        return buildString(unifiedLineEndings.length) {
            unifiedLineEndings.forEach { character ->
                when {
                    character == '\n' -> append('\n')
                    character.isWhitespace() -> append(' ')
                    else -> append(replacements[character] ?: character.toString())
                }
            }
        }
    }
}