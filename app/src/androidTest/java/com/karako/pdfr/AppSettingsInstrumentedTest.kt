package com.karako.pdfr

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karako.pdfr.data.AppSettings
import com.karako.pdfr.data.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Exercises the preference store and the locale-wrapped context on a device.
 * The wrap path is what makes the in-app language override work, so it is
 * checked against the real Hebrew resources rather than a fake.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        AppSettings.preferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AppSettings.preferences(context).edit().clear().commit()
    }

    @Test
    fun storesAndRestoresTheTheme() {
        assertEquals(ThemeMode.SYSTEM, AppSettings.themeMode(context))
        AppSettings.setThemeMode(context, ThemeMode.AMOLED)
        assertEquals(ThemeMode.AMOLED, AppSettings.themeMode(context))
    }

    @Test
    fun repairsAnUnknownStoredTheme() {
        AppSettings.preferences(context).edit()
            .putString(AppSettings.KEY_THEME, "not-a-theme")
            .commit()
        assertEquals(ThemeMode.SYSTEM, AppSettings.themeMode(context))
    }

    @Test
    fun storesAndRestoresTheLanguage() {
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.languageTag(context))
        AppSettings.setLanguageTag(context, "he")
        assertEquals("he", AppSettings.languageTag(context))
        AppSettings.setLanguageTag(context, AppSettings.SYSTEM_LANGUAGE)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.languageTag(context))
    }

    @Test
    fun repairsALanguageThatHasNoResources() {
        AppSettings.preferences(context).edit()
            .putString(AppSettings.KEY_LANGUAGE, "xx")
            .commit()
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.languageTag(context))
    }

    @Test
    fun systemLanguageReturnsTheOriginalContext() {
        assertSame(context, AppSettings.wrap(context))
    }

    @Test
    fun hebrewWrapsTheLocaleAndLayoutDirection() {
        AppSettings.setLanguageTag(context, "he")

        val wrapped = AppSettings.wrap(context)
        val configuration = wrapped.resources.configuration

        assertEquals(Locale.forLanguageTag("he"), configuration.locales[0])
        assertEquals(
            android.view.View.LAYOUT_DIRECTION_RTL,
            configuration.layoutDirection,
        )
        // A real string proves the values-iw folder was selected, not just the
        // configuration metadata.
        assertEquals("סגירה", wrapped.getString(R.string.close))
    }

    @Test
    fun englishOverrideUsesTheDefaultStrings() {
        AppSettings.setLanguageTag(context, "en")

        val wrapped = AppSettings.wrap(context)

        assertEquals(Locale.forLanguageTag("en"), wrapped.resources.configuration.locales[0])
        assertEquals(
            android.view.View.LAYOUT_DIRECTION_LTR,
            wrapped.resources.configuration.layoutDirection,
        )
        assertEquals("Close", wrapped.getString(R.string.close))
    }
}
