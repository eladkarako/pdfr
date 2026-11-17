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
 * [tag] is an empty string for "follow the system". [endonym] is the name of
 * the language in that language, so a Hebrew speaker can find עברית even while
 * the UI is English. [labelRes] is used instead when the entry is translated
 * (the system-default row).
 */
data class AppLanguage(
    val tag: String,
    val endonym: String,
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
        AppLanguage(SYSTEM_LANGUAGE, "", R.string.language_system),
        AppLanguage("en", "English"),
        AppLanguage("he", "עברית"),
    )

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
