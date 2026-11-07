package com.karako.pdfr.data

import java.io.File

/**
 * The document types PDFr advertises. MuPDF can sniff the real format from
 * the bytes, so these values are only used for file pickers, "Open with"
 * filters and the share sheet.
 *
 * MuPDF 1.28.5 accepts PDF, EPUB, MOBI, FB2, CBZ/CBT, XPS/OXPS, Markdown,
 * HTML/XHTML, plain text, SVG, common image formats, and the OOXML Office
 * formats (DOCX/XLSX/PPTX) plus HWPX. The old binary Office formats
 * (.doc/.xls/.ppt) have no MuPDF handler and are intentionally not mapped.
 */
object DocumentFormats {

    const val PDF = "application/pdf"
    const val EPUB = "application/epub+zip"
    const val XPS = "application/vnd.ms-xpsdocument"
    const val OXPS = "application/oxps"
    const val CBZ = "application/vnd.comicbook+zip"
    const val CBT = "application/x-cbt"
    const val FB2 = "application/x-fictionbook+xml"
    const val MOBI = "application/x-mobipocket-ebook"
    const val MARKDOWN = "text/markdown"
    const val HTML = "text/html"
    const val XHTML = "application/xhtml+xml"
    const val SVG = "image/svg+xml"

    const val DOCX =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    const val HWPX = "application/haansofthwpx"

    /**
     * MIME types offered to the system document picker. The image wildcard covers all
     * raster formats MuPDF understands; `application/octet-stream` lets the
     * user reach files whose provider does not report a useful MIME type.
     */
    val PICKER_MIME_TYPES = arrayOf(
        PDF,
        EPUB,
        XPS,
        OXPS,
        CBZ,
        CBT,
        "application/x-cbz",
        FB2,
        MOBI,
        MARKDOWN,
        "text/x-markdown",
        "text/plain",
        HTML,
        XHTML,
        SVG,
        "image/*",
        DOCX,
        XLSX,
        PPTX,
        HWPX,
        "application/octet-stream",
    )

    /** Extensions MuPDF renders as reflowable plain text. */
    private val plainTextExtensions = setOf("txt", "nfo", "text", "log")

    /**
     * Extensions MuPDF recognises only under a different suffix. The reader
     * makes a temporary copy with the canonical suffix before opening it.
     */
    private val mupdfAliases = mapOf(
        "nfo" to "txt",
        "markdown" to "md",
        "mdown" to "md",
        "mkd" to "md",
        "mkdn" to "md",
    )

    private val byExtension = mapOf(
        // Documents and e-books.
        "pdf" to PDF,
        "epub" to EPUB,
        "xps" to XPS,
        "oxps" to OXPS,
        "cbz" to CBZ,
        "cbt" to CBT,
        "fb2" to FB2,
        "mobi" to MOBI,
        "prc" to MOBI,
        "pdb" to MOBI,
        "md" to MARKDOWN,
        "markdown" to MARKDOWN,
        "mdown" to MARKDOWN,
        "mkd" to MARKDOWN,
        "mkdn" to MARKDOWN,
        "htm" to HTML,
        "html" to HTML,
        "xhtml" to XHTML,
        "txt" to "text/plain",
        "nfo" to "text/plain",
        "text" to "text/plain",
        "log" to "text/plain",
        "svg" to SVG,

        // OOXML Office documents and HWPX.
        "docx" to DOCX,
        "xlsx" to XLSX,
        "pptx" to PPTX,
        "hwpx" to HWPX,

        // Raster images.
        "bmp" to "image/bmp",
        "gif" to "image/gif",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "jpe" to "image/jpeg",
        "jfif" to "image/jpeg",
        "png" to "image/png",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "jp2" to "image/jp2",
        "jpx" to "image/jpx",
        "j2k" to "image/jp2",
        "pnm" to "image/x-portable-anymap",
        "pbm" to "image/x-portable-bitmap",
        "pgm" to "image/x-portable-greymap",
        "ppm" to "image/x-portable-pixmap",
        "pam" to "image/x-portable-arbitrarymap",
        "pfm" to "image/x-portable-floatmap",
        "psd" to "image/vnd.adobe.photoshop",
        "jxr" to "image/jxr",
        "wdp" to "image/vnd.ms-photo",
        "hdp" to "image/vnd.ms-photo",
        "jb2" to "image/x-jbig2",
        "jbig2" to "image/x-jbig2",
    )

    fun mimeTypeForName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return byExtension[extension]
    }

    /** True for `.txt`, `.nfo`, `.text` and `.log` documents. */
    fun isPlainTextName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in plainTextExtensions
    }

    /**
     * Returns the canonical suffix MuPDF understands for [name], or null when
     * the original extension already works. `.nfo` becomes `.txt`; Markdown
     * aliases become `.md`.
     */
    fun muPdfAliasForName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return mupdfAliases[extension]
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
