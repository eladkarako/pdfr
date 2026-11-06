package com.karako.pdfr.data

import java.io.File

/**
 * The document types PDFr advertises. MuPDF can sniff the real format from
 * the bytes, so these values are only used for file pickers, "Open with"
 * filters and the share sheet.
 */
object DocumentFormats {

    const val PDF = "application/pdf"
    const val EPUB = "application/epub+zip"
    const val XPS = "application/vnd.ms-xpsdocument"
    const val OXPS = "application/oxps"
    const val CBZ = "application/vnd.comicbook+zip"

    /** MIME types offered to the system document picker. */
    val PICKER_MIME_TYPES = arrayOf(
        PDF,
        EPUB,
        XPS,
        OXPS,
        CBZ,
        "application/x-cbz",
        "application/x-fictionbook+xml",
        "text/plain",
        "text/html",
        "application/xhtml+xml",
        "image/svg+xml",
        "application/octet-stream",
    )

    /** Extensions MuPDF renders as reflowable plain text. */
    private val plainTextExtensions = setOf("txt", "nfo", "text")

    private val byExtension = mapOf(
        "pdf" to PDF,
        "epub" to EPUB,
        "xps" to XPS,
        "oxps" to OXPS,
        "cbz" to CBZ,
        "fb2" to "application/x-fictionbook+xml",
        "svg" to "image/svg+xml",
        "txt" to "text/plain",
        "nfo" to "text/plain",
        "text" to "text/plain",
        "html" to "text/html",
        "htm" to "text/html",
        "xhtml" to "application/xhtml+xml",
    )

    fun mimeTypeForName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return byExtension[extension]
    }

    /** True for `.txt`, `.nfo` and `.text` documents. */
    fun isPlainTextName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in plainTextExtensions
    }

    fun mimeTypeForFile(file: File): String =
        mimeTypeForName(file.name) ?: "application/octet-stream"

    /**
     * Returns the extension (without the dot) when it is a short, sane
     * alphanumeric one. Opaque provider ids such as "msf:1234" are rejected.
     */
    fun extensionOf(name: String): String? {
        val extension = name.substringAfterLast('.', "")
        if (extension.length !in 1..8) return null
        if (!extension.all { it.isLetterOrDigit() }) return null
        return extension
    }
}
