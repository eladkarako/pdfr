package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes the language list that the settings picker and the platform per-app
 * language screen read.
 *
 * The `values-*` folders are the source of truth: adding a translation only
 * requires adding a folder, and no Kotlin or XML catalogue has to be edited in
 * parallel. Tags are BCP-47 so they can be passed to `Locale.forLanguageTag`.
 */
abstract class GenerateLanguageResources : DefaultTask() {

    @get:InputDirectory
    abstract val resDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        val tags = resDirectory.get().asFile.listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "strings.xml").isFile }
            .mapNotNull { folderTag(it.name) }
            .distinct()
            .sorted()

        require(tags.isNotEmpty()) {
            "No translated strings.xml folders found under ${resDirectory.get().asFile}"
        }

        val values = File(output, "values")
        values.mkdirs()
        File(values, "generated_languages.xml").writeText(
            buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                appendLine("<resources>")
                appendLine("    <!-- Generated from the values-* folders by GenerateLanguageResources. -->")
                appendLine("    <string-array name=\"supported_language_tags\" translatable=\"false\">")
                tags.forEach { appendLine("        <item>$it</item>") }
                appendLine("    </string-array>")
                appendLine("</resources>")
            },
            Charsets.UTF_8,
        )

        val xml = File(output, "xml")
        xml.mkdirs()
        File(xml, "locales_config.xml").writeText(
            buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                appendLine("<!-- Generated from the values-* folders by GenerateLanguageResources. -->")
                appendLine("<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">")
                tags.forEach { appendLine("    <locale android:name=\"$it\" />") }
                appendLine("</locale-config>")
            },
            Charsets.UTF_8,
        )
    }
}

/** Maps a resource folder name to the BCP-47 tag of the language it holds. */
internal fun folderTag(folder: String): String? {
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
