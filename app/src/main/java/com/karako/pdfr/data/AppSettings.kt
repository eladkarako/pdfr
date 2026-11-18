package com.karako.pdfr.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.karako.pdfr.R
import java.util.Locale

/** How PDFr chooses its colour scheme. */
enum class ThemeMode {
    /** Follow the device's light/dark setting and dynamic colour. */
    SYSTEM,

    /** Always use the light palette. */
    LIGHT,

    /** Use a dark palette with grey rather than pure-black surfaces. */
    DARK,

    /** Use a pure-black palette for OLED panels. */
    AMOLED,
    ;

    companion object {
        fun parse(name: String?): ThemeMode =
            runCatching { valueOf(name.orEmpty()) }.getOrDefault(SYSTEM)
    }
}

/**
 * One language offered by the settings dialog.
 *
 * [tag] is the value stored in preferences and passed to [Locale.forLanguageTag];
 * an empty string means "follow the system". [code] is the short resource
 * qualifier shown in the picker. Hebrew deliberately displays `iw`, because
 * that is the folder Android resolves (the modern `he` tag is still what gets
 * stored). [englishName] lets a speaker of any language recognise the row, and
 * [endonym] is the language's own name. [labelRes] replaces the three columns
 * for the system-default row, whose label is translated.
 */
data class AppLanguage(
    val tag: String,
    val code: String = "",
    val englishName: String = "",
    val endonym: String = "",
    val labelRes: Int? = null,
)

/**
 * Small, synchronous preference store for the app-wide appearance and
 * language choices.
 *
 * The values are read by [com.karako.pdfr.MainActivity] before the first
 * layout, so the stored language is already active when the UI is composed.
 * Theme changes only need a recomposition; a language change recreates the
 * activity because resources are resolved per context.
 */
object AppSettings {

    /** Preference file and keys. Kept public so tests can clean up. */
    const val PREFERENCES_NAME = "pdfr-settings"
    const val KEY_THEME = "theme"
    const val KEY_LANGUAGE = "language"

    /** Empty tag means "follow the system language". */
    const val SYSTEM_LANGUAGE = ""

    /** Languages that have a values-* resource folder in this build. */
    val languages: List<AppLanguage> = listOf(
        AppLanguage(SYSTEM_LANGUAGE, labelRes = R.string.language_system),
        AppLanguage("en", "en", "English", "English"),
        AppLanguage("he", "iw", "Hebrew", "עברית"),
        AppLanguage("ar", "ar", "Arabic", "العربية"),
        AppLanguage("fr", "fr", "French", "Français"),
        AppLanguage("ru", "ru", "Russian", "Русский"),
        AppLanguage("de", "de", "German", "Deutsch"),
    )

    /** The entry for [tag], or the system-default entry when it is unknown. */
    fun language(tag: String): AppLanguage =
        languages.firstOrNull { it.tag == tag } ?: languages.first()

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun themeMode(context: Context): ThemeMode =
        ThemeMode.parse(preferences(context).getString(KEY_THEME, ThemeMode.SYSTEM.name))

    fun setThemeMode(context: Context, mode: ThemeMode) {
        preferences(context).edit().putString(KEY_THEME, mode.name).apply()
    }

    fun languageTag(context: Context): String {
        val stored = preferences(context).getString(KEY_LANGUAGE, SYSTEM_LANGUAGE).orEmpty()
        return if (languages.any { it.tag == stored }) stored else SYSTEM_LANGUAGE
    }

    fun setLanguageTag(context: Context, tag: String) {
        val safe = if (languages.any { it.tag == tag }) tag else SYSTEM_LANGUAGE
        preferences(context).edit().putString(KEY_LANGUAGE, safe).apply()
    }

    /**
     * Returns [context] configured for the stored language.
     *
     * When no override is stored the original context is returned, so the
     * system locale keeps working exactly as before. The returned context
     * carries both the locale and its layout direction, which lets the
     * manifest's RTL support take effect for Hebrew.
     */
    fun wrap(context: Context): Context {
        val tag = languageTag(context)
        if (tag.isEmpty()) return context

        val locale = Locale.forLanguageTag(tag)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    /** Display name for [language], translated when it has a string resource. */
    fun displayName(context: Context, language: AppLanguage): String =
        language.labelRes?.let(context::getString) ?: language.endonym
}
