package com.baer.handtype.template

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class GlyphFileNameTest {

    private val savedLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun namesKeepAsciiDigitsWhenTheDeviceLocaleWritesItsOwnDigits() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))

        assertEquals("u0041_v3.png", GlyphFileName.of('A', 3))
    }

    @Test
    fun userGlyphsSavedWithArabicIndicDigitsStillLoad() {
        assertEquals('A', GlyphFileName.charOf("u0041_v٣.png"))
    }
}
