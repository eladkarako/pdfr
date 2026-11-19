package com.karako.pdfr

import android.content.Context
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karako.pdfr.data.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Resolves every offered language against the real APK resources.
 *
 * [AppSettingsInstrumentedTest] covers the preference store and the Hebrew and
 * English paths; this class walks the whole catalog so a missing folder, a
 * misnamed qualifier or a lost regional overlay fails on a device as well as in
 * the JVM resource checks.
 */
@RunWith(AndroidJUnit4::class)
class LanguageResourcesInstrumentedTest {

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

    private fun wrappedFor(tag: String): Context {
        AppSettings.setLanguageTag(context, tag)
        assertEquals(tag, AppSettings.languageTag(context))
        return AppSettings.wrap(context)
    }

    @Test
    fun everyOfferedLanguageWrapsItsCanonicalLocale() {
        AppSettings.languages.filter { it.tag.isNotEmpty() }.forEach { language ->
            val wrapped = wrappedFor(language.tag)
            assertEquals(
                "locale for ${language.tag}",
                Locale.forLanguageTag(language.tag),
                wrapped.resources.configuration.locales[0],
            )
        }
    }

    @Test
    fun everyOfferedLanguageSurvivesThePreferenceRoundTrip() {
        AppSettings.languages.filter { it.tag.isNotEmpty() }.forEach { language ->
            AppSettings.setLanguageTag(context, language.tag)
            assertEquals(language.tag, AppSettings.languageTag(context))
        }
        AppSettings.setLanguageTag(context, AppSettings.SYSTEM_LANGUAGE)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.languageTag(context))
    }

    @Test
    fun everyFullLanguageChangesTheCloseCaption() {
        val fallback = listOf("en", "en-GB")
        AppSettings.languages
            .filter { it.tag.isNotEmpty() && it.tag !in fallback }
            .forEach { language ->
                val close = wrappedFor(language.tag).getString(R.string.close)
                assertNotEquals("close for ${language.tag}", "Close", close)
                assertTrue("blank close for ${language.tag}", close.isNotBlank())
            }
    }

    @Test
    fun everyFullLanguageChangesTheShareCaption() {
        val fallback = listOf("en", "en-GB")
        AppSettings.languages
            .filter { it.tag.isNotEmpty() && it.tag !in fallback }
            .forEach { language ->
                val share = wrappedFor(language.tag).getString(R.string.share_document)
                assertNotEquals("share for ${language.tag}", "Share document", share)
            }
    }

    @Test
    fun japaneseSelectsJapaneseResources() {
        val wrapped = wrappedFor("ja")
        assertEquals("閉じる", wrapped.getString(R.string.close))
        assertEquals("ドキュメントを共有", wrapped.getString(R.string.share_document))
    }

    @Test
    fun simplifiedChineseSelectsSimplifiedResources() {
        val wrapped = wrappedFor("zh-CN")
        assertEquals("关闭", wrapped.getString(R.string.close))
        assertEquals("共享文档", wrapped.getString(R.string.share_document))
    }

    @Test
    fun traditionalChineseSelectsTraditionalResources() {
        val wrapped = wrappedFor("zh-TW")
        assertEquals("關閉", wrapped.getString(R.string.close))
        assertEquals("分享文件", wrapped.getString(R.string.share_document))
    }

    @Test
    fun koreanSelectsKoreanResources() {
        val wrapped = wrappedFor("ko")
        assertEquals("닫기", wrapped.getString(R.string.close))
        assertEquals("문서 공유", wrapped.getString(R.string.share_document))
    }

    @Test
    fun hindiSelectsHindiResources() {
        val wrapped = wrappedFor("hi")
        assertEquals("बंद करें", wrapped.getString(R.string.close))
        assertEquals("दस्तावेज़ साझा करें", wrapped.getString(R.string.share_document))
    }

    @Test
    fun europeanAndBrazilianPortugueseDiffer() {
        assertEquals("Definições…", wrappedFor("pt").getString(R.string.settings_open))
        assertEquals("Configurações…", wrappedFor("pt-BR").getString(R.string.settings_open))
    }

    @Test
    fun canadianFrenchOverridesTheFrenchFallback() {
        assertEquals(
            "Enregistré dans file.pdf.",
            wrappedFor("fr-CA").getString(R.string.saved_as, "file.pdf"),
        )
        assertEquals(
            "Enregistré sous file.pdf.",
            wrappedFor("fr").getString(R.string.saved_as, "file.pdf"),
        )
    }

    @Test
    fun latinAmericanSpanishOverridesTheSpanishFallback() {
        assertEquals("Configuración…", wrappedFor("es-419").getString(R.string.settings_open))
        assertEquals("Ajustes…", wrappedFor("es").getString(R.string.settings_open))
    }

    @Test
    fun britishEnglishUsesBritishSpellings() {
        val wrapped = wrappedFor("en-GB")
        assertEquals("Normalise…", wrapped.getString(R.string.normalize))
        assertEquals("Close", wrapped.getString(R.string.close))
    }

    @Test
    fun japaneseChineseKoreanAndHindiAreLeftToRight() {
        listOf("ja", "zh-CN", "zh-TW", "ko", "hi").forEach { tag ->
            assertEquals(
                "layout for $tag",
                View.LAYOUT_DIRECTION_LTR,
                wrappedFor(tag).resources.configuration.layoutDirection,
            )
        }
    }

    @Test
    fun arabicIsRightToLeft() {
        val wrapped = wrappedFor("ar")
        assertEquals("إغلاق", wrapped.getString(R.string.close))
        assertEquals(
            View.LAYOUT_DIRECTION_RTL,
            wrapped.resources.configuration.layoutDirection,
        )
    }

    @Test
    fun everyFullLanguageKeepsTheBrandName() {
        AppSettings.languages
            .filter { it.tag.isNotEmpty() }
            .forEach { language ->
                assertEquals("PDFr", wrappedFor(language.tag).getString(R.string.app_name))
            }
    }
}
