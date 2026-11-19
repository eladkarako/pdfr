package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * JVM checks for the language metadata behind the settings combo.
 *
 * The resource-completeness side lives in [TranslationResourcesTest]; this
 * class only reads the explicit catalog in [AppSettings.languages] and the JDK
 * locale parser, so it needs no Android runtime.
 */
class AppLanguageCatalogTest {

    private val all = AppSettings.languages
    private val offered = all.drop(1)

    @Test
    fun offersEighteenEntriesWithTheSystemRowFirst() {
        assertEquals(18, all.size)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, all.first().tag)
    }

    @Test
    fun everyOfferedLanguageHasAUniqueTag() {
        val tags = offered.map { it.tag }
        assertEquals(tags.size, tags.distinct().size)
        assertFalse(tags.contains(AppSettings.SYSTEM_LANGUAGE))
    }

    @Test
    fun everyOfferedLanguageHasAUniqueResourceCode() {
        val codes = offered.map { it.code }
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test
    fun everyOfferedLanguageHasAUniqueEnglishName() {
        val names = offered.map { it.englishName }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun everyOfferedLanguageHasAUniqueEndonym() {
        val names = offered.map { it.endonym }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun onlyTheSystemRowHasAnEmptyCodeAndNames() {
        val system = all.first()
        assertEquals("", system.code)
        assertEquals("", system.englishName)
        assertEquals("", system.endonym)
        assertNotNull(system.labelRes)

        offered.forEach {
            assertTrue(it.code.isNotBlank())
            assertTrue(it.englishName.isNotBlank())
            assertTrue(it.endonym.isNotBlank())
            assertNull(it.labelRes)
        }
    }

    @Test
    fun resourceCodesMatchTheAndroidFolderNames() {
        offered.forEach { language ->
            val expected = if (language.tag == "he") "iw" else language.tag
            assertEquals("folder for ${language.tag}", expected, language.code)
        }
    }

    @Test
    fun noTagOrCodeUsesTheResourceFolderPlusSign() {
        all.forEach {
            assertFalse(it.tag.contains('+'))
            assertFalse(it.code.contains('+'))
        }
    }

    @Test
    fun noTagOrCodeUsesUnderscores() {
        all.forEach {
            assertFalse(it.tag.contains('_'))
            assertFalse(it.code.contains('_'))
        }
    }

    @Test
    fun everyTagIsCanonicalBcp47() {
        offered.forEach {
            assertEquals(it.tag, Locale.forLanguageTag(it.tag).toLanguageTag())
        }
    }

    @Test
    fun everyTagResolvesToItsExpectedBaseLanguage() {
        val expected = mapOf(
            "en" to "en",
            "en-GB" to "en",
            "he" to "he",
            "ar" to "ar",
            "fr" to "fr",
            "fr-CA" to "fr",
            "de" to "de",
            "es" to "es",
            "es-419" to "es",
            "pt" to "pt",
            "pt-BR" to "pt",
            "ru" to "ru",
            "ja" to "ja",
            "zh-CN" to "zh",
            "zh-TW" to "zh",
            "ko" to "ko",
            "hi" to "hi",
        )
        assertEquals(expected.keys, offered.map { it.tag }.toSet())
        offered.forEach {
            assertEquals(
                "base language for ${it.tag}",
                expected.getValue(it.tag),
                Locale.forLanguageTag(it.tag).language,
            )
        }
    }

    @Test
    fun regionalVariantsCarryTheExpectedRegion() {
        val expected = mapOf(
            "en-GB" to "GB",
            "fr-CA" to "CA",
            "es-419" to "419",
            "pt-BR" to "BR",
            "zh-CN" to "CN",
            "zh-TW" to "TW",
        )
        expected.forEach { (tag, region) ->
            assertEquals("region for $tag", region, Locale.forLanguageTag(tag).country)
        }
    }

    @Test
    fun everyLanguageFamilyIsContiguousInTheCombo() {
        val positions = all.withIndex()
            .filter { it.value.tag.isNotEmpty() }
            .groupBy { Locale.forLanguageTag(it.value.tag).language }
            .mapValues { (_, entries) -> entries.map { it.index } }

        positions.forEach { (language, indices) ->
            assertEquals(
                "positions for $language",
                (indices.min()..indices.max()).toList(),
                indices,
            )
        }
    }

    @Test
    fun eachRegionalVariantImmediatelyFollowsItsBaseLanguage() {
        listOf(
            "en" to "en-GB",
            "fr" to "fr-CA",
            "es" to "es-419",
            "pt" to "pt-BR",
        ).forEach { (base, variant) ->
            val tags = all.map { it.tag }
            assertEquals("$variant follows $base", tags.indexOf(base) + 1, tags.indexOf(variant))
        }
        assertEquals(
            "zh-CN and zh-TW stay together",
            all.map { it.tag }.indexOf("zh-CN") + 1,
            all.map { it.tag }.indexOf("zh-TW"),
        )
    }

    @Test
    fun hebrewStoresTheModernTagButShowsTheLegacyFolderCode() {
        val hebrew = AppSettings.language("he")
        assertEquals("he", hebrew.tag)
        assertEquals("iw", hebrew.code)
        assertEquals("Hebrew", hebrew.englishName)
        assertEquals("עברית", hebrew.endonym)
    }

    @Test
    fun theLegacyHebrewAliasStillResolvesToHebrew() {
        assertEquals("he", Locale.forLanguageTag("iw").language)
        assertEquals("he", Locale.forLanguageTag("he").language)
    }

    @Test
    fun onlyHebrewAndArabicAreRightToLeft() {
        val rightToLeft = setOf("ar", "he", "fa", "ur", "yi", "dv", "ps", "sd", "ug")
        val ours = offered
            .filter { Locale.forLanguageTag(it.tag).language in rightToLeft }
            .map { it.tag }
            .toSet()
        assertEquals(setOf("he", "ar"), ours)
    }

    @Test
    fun japaneseChineseAndKoreanUseLeftToRightLayout() {
        val rightToLeft = setOf("ar", "he", "fa", "ur", "yi", "dv", "ps", "sd", "ug")
        listOf("ja", "zh-CN", "zh-TW", "ko", "hi").forEach { tag ->
            assertFalse(tag, Locale.forLanguageTag(tag).language in rightToLeft)
        }
    }

    @Test
    fun languageLookupIsExactAndFallsBackToSystem() {
        all.forEach { assertEquals(it, AppSettings.language(it.tag)) }
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language("xx").tag)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language("EN").tag)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language("en-gb").tag)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language("pt-br").tag)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language("he-IL").tag)
    }

    @Test
    fun everyLanguageHasADistinctDisplayIdentity() {
        offered.forEach { language ->
            assertNotEquals(language.tag, language.englishName)
            assertNotEquals(language.code, language.endonym)
        }
    }

    @Test
    fun englishAndItsBritishVariantShareTheBaseLanguage() {
        val english = AppSettings.language("en")
        val british = AppSettings.language("en-GB")
        assertEquals(Locale.forLanguageTag("en").language, Locale.forLanguageTag("en-GB").language)
        assertNotEquals(english.tag, british.tag)
        assertNotEquals(english.englishName, british.englishName)
    }
}
