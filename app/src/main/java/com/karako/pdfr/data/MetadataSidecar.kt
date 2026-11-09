package com.karako.pdfr.data

import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Properties

/**
 * Stores edited metadata for formats MuPDF cannot rewrite (EPUB, images,
 * plain text, ...). PDF metadata is written into the PDF itself instead.
 *
 * The sidecar lives in the document's own cache directory, so
 * [DocumentStore] removes it together with the document when the reader moves
 * to another file. Only fields that were present in the draft are stored; an
 * empty value deliberately overrides the file's own metadata with "removed".
 */
object MetadataSidecar {

    private const val SUFFIX = ".pdfr-meta"

    fun fileFor(document: File): File = File(document.parentFile, ".${document.name}$SUFFIX")

    fun read(document: File): Map<String, String>? {
        val file = fileFor(document)
        if (!file.isFile) return null
        return try {
            val properties = Properties()
            InputStreamReader(file.inputStream(), Charsets.UTF_8).use(properties::load)
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun write(document: File, values: Map<String, String>) {
        val file = fileFor(document)
        if (values.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val properties = Properties()
        values.forEach { (key, value) -> properties.setProperty(key, value) }
        val staging = File(file.parentFile, "${file.name}.tmp")
        try {
            OutputStreamWriter(staging.outputStream(), Charsets.UTF_8).use { writer ->
                properties.store(writer, null)
            }
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Could not replace $file")
            }
            if (!staging.renameTo(file)) {
                staging.copyTo(file, overwrite = true)
                staging.delete()
            }
        } catch (t: Throwable) {
            staging.delete()
            throw t
        }
    }

    fun delete(document: File) {
        fileFor(document).delete()
    }
}
