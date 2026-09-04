package com.baer.handtype.feature.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

data class AppSettings(
    val themePreference: ThemePreference = ThemePreference.System,
    val languagePreference: LanguagePreference = LanguagePreference.System,
    val onboardingComplete: Boolean = false,
)

enum class ThemePreference {
    System,
    Light,
    Dark,
}

enum class LanguagePreference(val languageTag: String?) {
    System(null),
    English("en"),
    Spanish("es"),
    PortugueseBrazil("pt-BR"),
    Russian("ru"),
    Japanese("ja"),
    Hindi("hi"),
    German("de"),
    French("fr"),
    Korean("ko"),
    ChineseSimplified("zh-CN"),
}

class AppSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): AppSettings {
        return AppSettings(
            themePreference = prefs.getString(KEY_THEME, null)
                ?.let { stored -> ThemePreference.values().firstOrNull { it.name == stored } }
                ?: ThemePreference.System,
            languagePreference = prefs.getString(KEY_LANGUAGE, null)
                ?.let { stored -> LanguagePreference.values().firstOrNull { it.name == stored } }
                ?: LanguagePreference.System,
            onboardingComplete = prefs.getBoolean(KEY_ONBOARDING, false),
        )
    }

    fun write(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.themePreference.name)
            .putString(KEY_LANGUAGE, settings.languagePreference.name)
            .putBoolean(KEY_ONBOARDING, settings.onboardingComplete)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "handtype_app_settings"
        const val KEY_THEME = "theme_preference"
        const val KEY_LANGUAGE = "language_preference"
        const val KEY_ONBOARDING = "onboarding_complete"
    }
}

fun Context.localizedFor(languagePreference: LanguagePreference): Context {
    val tag = languagePreference.languageTag ?: return this
    val locale = Locale.forLanguageTag(tag)
    val config = Configuration(resources.configuration)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.setLocales(LocaleList(locale))
    } else {
        @Suppress("DEPRECATION")
        config.locale = locale
    }
    return LocalizedContext(this, config)
}

/**
 * Swaps only the resources, keeping [base] in the ContextWrapper chain. Handing Compose a bare
 * `createConfigurationContext` result instead hides the Activity, so anything that walks the chain
 * for it - `LocalActivityResultRegistryOwner`, `findActivity()`, `startActivity` - breaks.
 */
private class LocalizedContext(base: Context, config: Configuration) : ContextWrapper(base) {
    private val localizedResources: Resources = base.createConfigurationContext(config).resources

    override fun getResources(): Resources = localizedResources
}
