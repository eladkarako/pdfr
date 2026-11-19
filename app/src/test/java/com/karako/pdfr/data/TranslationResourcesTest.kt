package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the shipped `strings.xml` files against the base English resources.
 *
 * These are JVM tests: they read the source tree instead of a compiled APK, so
 * a missing folder, an untranslated string or a dropped `%1$s` fails before the
 * build even reaches the resource merger.
 */
class TranslationResourcesTest {

    private val resDir: File = moduleRoot().resolve("src/main/res")
    private val baseFolder = "values"
    private val fullFolders = listOf(
        "values-iw",
        "values-ar",
        "values-fr",
        "values-de",
        "values-es",
        "values-pt",
        "values-pt-rBR",
        "values-ru",
        "values-ja",
        "values-zh-rCN",
        "values-zh-rTW",
        "values-ko",
        "values-hi",
    )
    private val overlayFolders = listOf("values-en-rGB", "values-fr-rCA", "values-b+es+419")

    private val baseStrings by lazy { strings(baseFolder) }
    private val basePlurals by lazy { plurals(baseFolder) }

    @Test
    fun baseResourcesParseAndAreSubstantial() {
        assertTrue("base strings", baseStrings.size > 150)
        assertTrue("base plurals", basePlurals.size >= 4)
        assertTrue(baseStrings.containsKey("app_name"))
        assertTrue(baseStrings.containsKey("share_document"))
    }

    @Test
    fun onlyTheExpectedFoldersShipStrings() {
        val expected = (listOf(baseFolder) + fullFolders + overlayFolders).toSet()
        val actual = resDir.listFiles()
            .orEmpty()
            .filter { File(it, "strings.xml").isFile }
            .map { it.name }
            .toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun everyOfferedLanguageHasAResourceFolder() {
        AppSettings.languages.forEach { language ->
            val folder = when {
                language.tag.isEmpty() -> baseFolder
                language.tag == "en" -> baseFolder
                language.tag == "es-419" -> "values-b+es+419"
                language.tag.contains('-') ->
                    "values-${language.tag.substringBefore('-')}-r${language.tag.substringAfter('-')}"
                else -> "values-${language.code}"
            }
            assertTrue(
                "missing $folder for ${language.englishName}",
                File(resDir, "$folder/strings.xml").isFile,
            )
        }
    }

    @Test
    fun fullTranslationsDefineEveryBaseStringExceptTheNonTranslatableHint() {
        fullFolders.forEach { folder ->
            val missing = baseStrings.keys - strings(folder).keys
            assertEquals("$folder missing strings", setOf("date_hint"), missing)
        }
    }

    @Test
    fun fullTranslationsDefineEveryBasePlural() {
        fullFolders.forEach { folder ->
            assertEquals("$folder plural names", basePlurals.keys, plurals(folder).keys)
        }
    }

    @Test
    fun fullTranslationsAddNoUnknownStrings() {
        fullFolders.forEach { folder ->
            assertEquals("$folder extra strings", emptySet<String>(), strings(folder).keys - baseStrings.keys)
        }
    }

    @Test
    fun fullTranslationsAddNoUnknownPlurals() {
        fullFolders.forEach { folder ->
            assertEquals("$folder extra plurals", emptySet<String>(), plurals(folder).keys - basePlurals.keys)
        }
    }

    @Test
    fun fullTranslationsKeepEveryPlaceholder() {
        fullFolders.forEach { folder ->
            val translated = strings(folder)
            baseStrings.forEach { (name, value) ->
                if (name != "date_hint") {
                    assertEquals(
                        "$folder/$name placeholders",
                        placeholders(value),
                        placeholders(translated.getValue(name)),
                    )
                }
            }
        }
    }

    @Test
    fun fullTranslationsKeepEveryPlaceholderInPlurals() {
        fullFolders.forEach { folder ->
            val translated = plurals(folder)
            basePlurals.forEach { (name, items) ->
                val target = translated.getValue(name)
                val shared = items.keys intersect target.keys
                assertTrue("$folder/$name has no shared quantity", shared.isNotEmpty())
                // Some languages add a distinct singular that needs no number,
                // so compare the placeholders used across the whole plural.
                assertEquals(
                    "$folder/$name placeholders",
                    placeholders(items.values.joinToString(" ")).toSet(),
                    placeholders(target.values.joinToString(" ")).toSet(),
                )
            }
        }
    }

    @Test
    fun everyPluralKeepsAnOtherQuantity() {
        (listOf(baseFolder) + fullFolders).forEach { folder ->
            plurals(folder).forEach { (name, items) ->
                assertTrue("$folder/$name has no other quantity", items.containsKey("other"))
            }
        }
    }

    @Test
    fun fullTranslationsHaveNoBlankValues() {
        fullFolders.forEach { folder ->
            strings(folder).forEach { (name, value) ->
                assertTrue("$folder/$name is blank", value.isNotBlank())
            }
            plurals(folder).forEach { (name, items) ->
                items.forEach { (quantity, value) ->
                    assertTrue("$folder/$name/$quantity is blank", value.isNotBlank())
                }
            }
        }
    }

    @Test
    fun baseStringsHaveNoBlankValues() {
        baseStrings.forEach { (name, value) ->
            assertTrue("base/$name is blank", value.isNotBlank())
        }
    }

    @Test
    fun onlyTheDateHintIsMarkedNotTranslatable() {
        val untranslatable = elements(baseFolder, "string")
            .filter { it.getAttribute("translatable") == "false" }
            .map { it.getAttribute("name") }
        assertEquals(listOf("date_hint"), untranslatable)
    }

    @Test
    fun overlaysOnlyOverrideKnownStrings() {
        overlayFolders.forEach { folder ->
            assertEquals("$folder extra strings", emptySet<String>(), strings(folder).keys - baseStrings.keys)
            assertEquals("$folder extra plurals", emptySet<String>(), plurals(folder).keys - basePlurals.keys)
        }
    }

    @Test
    fun overlaysAreSmallFallbacksAndFullLocalesAreComplete() {
        overlayFolders.forEach { folder ->
            val size = strings(folder).size
            assertTrue("$folder has $size strings", size in 1..20)
        }
        fullFolders.forEach { folder ->
            assertTrue("$folder is too small", strings(folder).size > 150)
        }
    }

    @Test
    fun britishEnglishUsesBritishSpellings() {
        val british = strings("values-en-rGB")
        assertTrue(british.getValue("normalize").contains("Normalise"))
        assertTrue(british.getValue("normalize_title").contains("Normalise"))
        assertTrue(british.getValue("normalize_linearize").contains("Linearise"))
        assertFalse(british.getValue("normalize").contains("Normalize"))
        assertTrue(baseStrings.getValue("normalize").contains("Normalize"))
    }

    @Test
    fun canadianFrenchOverridesSavedAs() {
        val canadian = strings("values-fr-rCA").getValue("saved_as")
        val french = strings("values-fr").getValue("saved_as")
        assertTrue(canadian.contains("dans"))
        assertTrue(french.contains("sous"))
        assertFalse(canadian == french)
    }

    @Test
    fun latinAmericanSpanishOverridesSettings() {
        assertEquals("Configuración…", strings("values-b+es+419").getValue("settings_open"))
        assertEquals("Ajustes…", strings("values-es").getValue("settings_open"))
    }

    @Test
    fun everyLocaleFileStartsWithAnXmlDeclaration() {
        (listOf(baseFolder) + fullFolders + overlayFolders).forEach { folder ->
            val firstLine = File(resDir, "$folder/strings.xml").readLines().first()
            assertTrue("$folder first line", firstLine.startsWith("<?xml"))
        }
    }

    @Test
    fun everyLocaleFileHasNoDuplicateStringNames() {
        (listOf(baseFolder) + fullFolders + overlayFolders).forEach { folder ->
            val names = elements(folder, "string").map { it.getAttribute("name") }
            assertEquals("$folder duplicate strings", names.size, names.distinct().size)
        }
    }

    @Test
    fun everyLocaleFileHasNoDuplicatePluralNames() {
        (listOf(baseFolder) + fullFolders + overlayFolders).forEach { folder ->
            val names = elements(folder, "plurals").map { it.getAttribute("name") }
            assertEquals("$folder duplicate plurals", names.size, names.distinct().size)
        }
    }

    @Test
    fun fullTranslationsKeepTheBrandName() {
        fullFolders.forEach { folder ->
            assertEquals("PDFr", strings(folder).getValue("app_name"))
        }
    }

    @Test
    fun fullTranslationsTranslateTheToolbarCaptions() {
        val captions = listOf(
            "open_another",
            "share_document",
            "save_as",
            "document_info",
            "more_options",
        )
        fullFolders.forEach { folder ->
            val translated = strings(folder)
            captions.forEach { name ->
                assertFalse(
                    "$folder/$name is still English",
                    translated.getValue(name) == baseStrings.getValue(name),
                )
            }
        }
    }

    @Test
    fun fullTranslationsTranslateTheEmptyStateAndErrors() {
        fullFolders.forEach { folder ->
            val translated = strings(folder)
            listOf("empty_title", "empty_body", "error_title", "error_generic").forEach { name ->
                assertFalse(
                    "$folder/$name is still English",
                    translated.getValue(name) == baseStrings.getValue(name),
                )
            }
        }
    }

    @Test
    fun fullTranslationsTranslateTheSettingsAndPasswordCopy() {
        fullFolders.forEach { folder ->
            val translated = strings(folder)
            listOf("settings_title", "settings_note", "password_body", "working").forEach { name ->
                assertFalse(
                    "$folder/$name is still English",
                    translated.getValue(name) == baseStrings.getValue(name),
                )
            }
        }
    }

    @Test
    fun localeFilesAreUtf8WithoutABom() {
        (listOf(baseFolder) + fullFolders + overlayFolders).forEach { folder ->
            val file = File(resDir, "$folder/strings.xml")
            val bytes = file.readBytes()
            assertFalse(
                "$folder starts with a UTF-8 BOM",
                bytes.size >= 3 &&
                    bytes[0] == 0xEF.toByte() &&
                    bytes[1] == 0xBB.toByte() &&
                    bytes[2] == 0xBF.toByte(),
            )
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
        }
    }

    @Test
    fun everyPlaceholderIsPositional() {
        val folders = listOf(baseFolder) + fullFolders + overlayFolders
        folders.forEach { folder ->
            strings(folder).forEach { (name, value) ->
                assertEquals("$folder/$name has a stray %", emptyList<String>(), strayPercents(value))
            }
            plurals(folder).forEach { (name, items) ->
                items.forEach { (quantity, value) ->
                    assertEquals(
                        "$folder/$name/$quantity has a stray %",
                        emptyList<String>(),
                        strayPercents(value),
                    )
                }
            }
        }
    }

    @Test
    fun everyFullTranslationCoversTheRebuildActions() {
        val names = listOf(
            "reorder_pages",
            "reorder_body",
            "rotate_pages",
            "page_size_body",
            "normalize_body",
            "fix_pdf",
            "rewrite_failed",
        )
        fullFolders.forEach { folder ->
            val translated = strings(folder)
            names.forEach { name ->
                assertTrue("$folder missing $name", translated.containsKey(name))
                assertFalse(
                    "$folder/$name is still English",
                    translated.getValue(name) == baseStrings.getValue(name),
                )
            }
        }
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

    private fun document(folder: String): Document {
        val file = File(resDir, "$folder/strings.xml")
        assertTrue("missing ${file.path}", file.isFile)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    }

    private fun elements(folder: String, tag: String): List<Element> {
        val nodes = document(folder).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun strings(folder: String): Map<String, String> =
        elements(folder, "string").associate { it.getAttribute("name") to it.textContent }

    private fun plurals(folder: String): Map<String, Map<String, String>> =
        elements(folder, "plurals").associate { plural ->
            val items = (0 until plural.childNodes.length)
                .map { plural.childNodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.tagName == "item" }
                .associate { it.getAttribute("quantity") to it.textContent }
            plural.getAttribute("name") to items
        }

    private val placeholder = Regex("""%\d+\$[sd]""")

    private fun placeholders(text: String): List<String> =
        placeholder.findAll(text).map { it.value }.sorted().toList()

    private fun strayPercents(text: String): List<String> =
        Regex("""%(?!\d+\$[sd])""").findAll(text).map { it.value }.toList()
}
