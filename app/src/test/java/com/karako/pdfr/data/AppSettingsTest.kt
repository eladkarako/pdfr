package com.karako.pdfr.data

import com.karako.pdfr.ui.theme.isDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM checks for the parts of [AppSettings] that do not need a Context. */
class AppSettingsTest {

    @Test
    fun parsesEveryStoredTheme() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.parse(mode.name))
        }
    }

    @Test
    fun repairsAnUnknownOrMissingTheme() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("neon"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("amoled"))
    }

    @Test
    fun offersTheSystemFirstThenEnglishAndHebrew() {
        val languages = AppSettings.languages
        assertEquals(AppSettings.SYSTEM_LANGUAGE, languages.first().tag)
        assertEquals(listOf("", "en", "he"), languages.map { it.tag })
        assertEquals(languages.size, languages.map { it.tag }.distinct().size)
    }

    @Test
    fun onlyTheSystemEntryHasNoEndonym() {
        val system = AppSettings.languages.first()
        assertEquals("", system.endonym)
        AppSettings.languages.drop(1).forEach { assertTrue(it.endonym.isNotBlank()) }
    }

    @Test
    fun forcedLightAndDarkIgnoreTheSystemSetting() {
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = false))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertTrue(ThemeMode.DARK.isDark(systemDark = true))
        assertTrue(ThemeMode.AMOLED.isDark(systemDark = false))
        assertTrue(ThemeMode.AMOLED.isDark(systemDark = true))
    }

    @Test
    fun systemThemeFollowsTheDevice() {
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
    }
}
