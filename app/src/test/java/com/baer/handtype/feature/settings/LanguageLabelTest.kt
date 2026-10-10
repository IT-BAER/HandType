package com.baer.handtype.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageLabelTest {
    @Test fun nativeNamesStartWithACapitalLetter() {
        assertEquals("Español", nativeLanguageName("es"))
        assertEquals("Français", nativeLanguageName("fr"))
        assertEquals("Русский", nativeLanguageName("ru"))
        assertEquals("Português (Brasil)", nativeLanguageName("pt-BR"))
    }

    @Test fun scriptsWithoutCaseStayUnchanged() {
        assertEquals("日本語", nativeLanguageName("ja"))
    }
}
