package com.karako.pdfr.data

import com.karako.pdfr.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * JVM checks for the language metadata behind the settings combo.
 *
 * The picker no longer has a hand-maintained catalogue: the tags come from the
 * `values-*` folders through the generated `supported_language_tags` array.
 * These tests walk the source folders with the same folder-to-tag rules, then
 * check the rows [AppSettings.offeredLanguages] builds from them.
 */
class AppLanguageCatalogTest {

    private val expectedTags = listOf(
        "ar", "de", "en", "en-GB", "es", "es-419", "fr", "fr-CA", "he",
        "hi", "ja", "ko", "pt", "pt-BR", "ru", "zh-CN", "zh-TW",
    )

    private val offered = AppSettings.offeredLanguages(expectedTags)
    private val system = AppLanguage(
        AppSettings.SYSTEM_LANGUAGE,
        labelRes = R.string.language_system,
    )
    private val all = listOf(system) + offered

    @Test
    fun everyStringsFolderMapsToAnExpectedTag() {
        val folders = moduleRoot().resolve("src/main/res").listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "strings.xml").isFile }
            .mapNotNull { folderTag(it.name) }
            .toSet()

        assertEquals(expectedTags.toSet(), folders)
    }

    @Test
    fun offersEveryTranslatedLanguageWithTheSystemRowFirst() {
        assertEquals(expectedTags.size, offered.size)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, all.first().tag)
        assertEquals(expectedTags.size + 1, all.size)
    }

    @Test
    fun everyOfferedLanguageHasAUniqueTag() {
        val tags = offered.map { it.tag }
        assertEquals(tags.size, tags.distinct().size)
        assertFalse(tags.contains(AppSettings.SYSTEM_LANGUAGE))
    }

    @Test
    fun everyOfferedLanguageHasAUniqueEnglishName() {
        val names = offered.map { it.englishName }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun everyOfferedLanguageHasAUniqueNativeName() {
        val names = offered.map { it.endonym }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun onlyTheSystemRowHasAnEmptyName() {
        assertEquals("", system.englishName)
        assertEquals("", system.endonym)
        assertNotNull(system.labelRes)

        offered.forEach {
            assertTrue("blank English name for ${it.tag}", it.englishName.isNotBlank())
            assertTrue("blank native name for ${it.tag}", it.endonym.isNotBlank())
            assertNull(it.labelRes)
        }
    }

    @Test
    fun noTagUsesTheResourceFolderPlusSignOrUnderscore() {
        all.forEach {
            assertFalse(it.tag.contains('+'))
            assertFalse(it.tag.contains('_'))
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
    fun entriesAreSortedByEnglishName() {
        val names = offered.map { it.englishName }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun eachRegionalVariantImmediatelyFollowsItsBaseLanguage() {
        val tags = all.map { it.tag }
        listOf(
            "en" to "en-GB",
            "fr" to "fr-CA",
            "es" to "es-419",
            "pt" to "pt-BR",
        ).forEach { (base, variant) ->
            assertEquals("$variant follows $base", tags.indexOf(base) + 1, tags.indexOf(variant))
        }
        assertEquals(
            "zh-CN and zh-TW stay together",
            tags.indexOf("zh-CN") + 1,
            tags.indexOf("zh-TW"),
        )
    }

    @Test
    fun hebrewUsesTheModernTagWithItsNativeName() {
        val hebrew = AppSettings.language(all, "he")
        assertEquals("he", hebrew.tag)
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
        all.forEach { assertEquals(it, AppSettings.language(all, it.tag)) }
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language(all, "xx").tag)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language(all, "EN").tag)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language(all, "en-gb").tag)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language(all, "pt-br").tag)
        assertEquals(AppSettings.SYSTEM_LANGUAGE, AppSettings.language(all, "he-IL").tag)
    }

    @Test
    fun englishAndItsBritishVariantHaveDistinctNames() {
        val english = AppSettings.language(all, "en")
        val british = AppSettings.language(all, "en-GB")
        assertEquals(Locale.forLanguageTag("en").language, Locale.forLanguageTag("en-GB").language)
        assertNotEquals(english.tag, british.tag)
        assertNotEquals(english.englishName, british.englishName)
    }

    @Test
    fun generatorRulesMapEveryShippedFolder() {
        val expected = mapOf(
            "values" to "en",
            "values-iw" to "he",
            "values-en-rGB" to "en-GB",
            "values-pt-rBR" to "pt-BR",
            "values-b+es+419" to "es-419",
            "values-zh-rCN" to "zh-CN",
        )
        expected.forEach { (folder, tag) -> assertEquals(folder, tag, folderTag(folder)) }
    }

    private fun moduleRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            if (File(directory, "src/main/res/values/strings.xml").isFile) return directory
            if (File(directory, "app/src/main/res/values/strings.xml").isFile) {
                return File(directory, "app")
            }
            directory = directory.parentFile
        }
        error("Could not locate app/src/main/res from ${System.getProperty("user.dir")}")
    }

    /** Mirror of the build script rule; kept here so a mismatch fails a test. */
    private fun folderTag(folder: String): String? {
        if (folder == "values") return "en"
        val prefix = "values-"
        if (!folder.startsWith(prefix)) return null

        val qualifier = folder.removePrefix(prefix)
        if (qualifier.startsWith("b+")) {
            return qualifier.removePrefix("b+").replace('+', '-')
        }

        val parts = qualifier.split("-r")
        val language = when (parts[0]) {
            "iw" -> "he"
            else -> parts[0]
        }
        return if (parts.size == 1) language else "$language-${parts[1]}"
    }
}
