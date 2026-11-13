package com.baer.handtype

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.feature.settings.LanguagePreference
import com.baer.handtype.feature.settings.localizedFor
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** The in-app language picker and the system's per-app language list reach the same translations. */
@RunWith(AndroidJUnit4::class)
class AppLanguageTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyPickerLanguageLoadsItsOwnStrings() {
        val english = context.localizedFor(LanguagePreference.English).getString(R.string.settings_language_title)
        LanguagePreference.values().filter { it.languageTag != null && it != LanguagePreference.English }.forEach {
            assertNotEquals(it.name, english, context.localizedFor(it).getString(R.string.settings_language_title))
        }
        // The picker tag "id" resolves to values-in.
        assertEquals("Bahasa", context.localizedFor(LanguagePreference.Indonesian).getString(R.string.settings_language_title))
    }

    @Test
    fun systemLanguageListMatchesThePicker() {
        val parser = context.resources.getXml(R.xml.locale_config)
        val configTags = buildList {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    add(parser.getAttributeValue(ANDROID_NS, "name"))
                }
            }
        }
        val pickerTags = LanguagePreference.values().mapNotNull { it.languageTag }

        assertEquals(pickerTags.size, configTags.size)
        assertEquals(pickerTags.map(::languageTitle).toSet(), configTags.map(::languageTitle).toSet())
    }

    private fun languageTitle(tag: String): String {
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) }
        return context.createConfigurationContext(config).getString(R.string.settings_language_title)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
