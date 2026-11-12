package com.karako.pdfr.data

import java.io.File
import java.io.IOException

/**
 * The document types PDFr advertises. MuPDF can sniff the real format from
 * the bytes, so these values are only used for file pickers, "Open with"
 * filters and the share sheet.
 *
 * MuPDF 1.28.5 accepts PDF, EPUB, MOBI, FB2, CBZ/CBT, XPS/OXPS, Markdown,
 * HTML/XHTML, plain text, SVG and common image formats. Office documents
 * (DOCX/XLSX/PPTX/HWPX) are intentionally not advertised yet.
 *
 * Text-based source and configuration files (JSON, YAML, CSV, C/C++, JS,
 * shell scripts and so on) are read as plain text. MuPDF has no handler for
 * their suffixes, so [muPdfAliasForName] gives them a temporary `.txt` copy.
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
    const val TEXT = "text/plain"
    const val CSV = "text/csv"
    const val TSV = "text/tab-separated-values"
    const val JSON = "application/json"
    const val XML = "application/xml"
    const val YAML = "application/x-yaml"
    const val TOML = "application/toml"
    const val JAVASCRIPT = "application/javascript"
    const val SHELL = "application/x-sh"

    /**
     * MIME types offered to the system document picker. `text/plain` and the
     * text wildcard cover source/configuration files; the image wildcard
     * covers all raster formats MuPDF understands. `application/octet-stream`
     * lets the user reach files whose provider does not report a useful MIME
     * type; PDFr then sniffs whether the bytes are text.
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
        TEXT,
        "text/*",
        HTML,
        XHTML,
        SVG,
        CSV,
        TSV,
        JSON,
        "application/x-ndjson",
        "application/jsonl",
        XML,
        YAML,
        "application/yaml",
        TOML,
        JAVASCRIPT,
        "application/x-javascript",
        "application/ecmascript",
        SHELL,
        "application/x-shellscript",
        "application/sql",
        "application/x-httpd-php",
        "application/x-perl",
        "application/x-python",
        "application/x-ruby",
        "application/x-tex",
        "application/x-properties",
        "image/*",
        "application/octet-stream",
    )

    /**
     * Extensions MuPDF should render as reflowable plain text. This is broader
     * than MuPDF's own suffix list: code and configuration files are text even
     * though MuPDF has no dedicated handler for them.
     */
    private val plainTextExtensions = setOf(
        // Plain text, notes and logs.
        "txt", "text", "log", "nfo",
        // Configuration and data files.
        "cfg", "ini", "conf", "config", "properties", "env",
        "yaml", "yml", "toml", "json", "jsonl", "ndjson", "csv", "tsv",
        "xml", "plist", "reg", "inf", "diff", "patch",
        // Source code.
        "c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx",
        "cs", "java", "kt", "kts", "groovy", "scala", "swift", "m", "mm",
        "js", "mjs", "cjs", "jsx", "ts", "tsx", "py", "pyw", "rb", "php",
        "go", "rs", "dart", "lua", "pl", "pm", "r", "jl", "sql",
        "sh", "bash", "zsh", "fish", "ksh", "csh", "bat", "cmd", "ps1", "psm1",
        "css", "scss", "sass", "less", "vue", "svelte",
        "tex", "bib", "asm", "s", "v", "vhd", "vhdl",
    )

    /** Plain-text suffixes MuPDF handles directly, so no temporary copy is needed. */
    private val nativeTextExtensions = setOf("txt", "text", "log")

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

    /** MIME types that describe text even though they are not under text wildcard. */
    private val applicationTextMimeTypes = setOf(
        JSON,
        "application/x-json",
        XML,
        YAML,
        "application/yaml",
        TOML,
        JAVASCRIPT,
        "application/x-javascript",
        "application/ecmascript",
        SHELL,
        "application/x-shellscript",
        "application/x-csh",
        "application/sql",
        "application/x-ndjson",
        "application/jsonl",
        "application/ndjson",
        "application/x-httpd-php",
        "application/x-php",
        "application/x-perl",
        "application/x-python",
        "application/x-ruby",
        "application/x-tex",
        "application/x-properties",
        "application/csv",
        "application/tsv",
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
        "svg" to SVG,

        // Text and data. Other source/configuration suffixes fall back to
        // text/plain through plainTextExtensions.
        "txt" to TEXT,
        "nfo" to TEXT,
        "text" to TEXT,
        "log" to TEXT,
        "csv" to CSV,
        "tsv" to TSV,
        "json" to JSON,
        "jsonl" to "application/x-ndjson",
        "ndjson" to "application/x-ndjson",
        "xml" to XML,
        "yaml" to YAML,
        "yml" to YAML,
        "toml" to TOML,
        "js" to JAVASCRIPT,
        "mjs" to JAVASCRIPT,
        "cjs" to JAVASCRIPT,
        "sh" to SHELL,
        "bash" to SHELL,
        "zsh" to SHELL,
        "fish" to SHELL,
        "ksh" to SHELL,
        "csh" to SHELL,

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

    /**
     * Suffixes handled as raster images. MuPDF reports its own names for
     * these (`JPEG`, `PNG`, ...), while [DocumentInfo.isImage] also accepts the
     * cache file's suffix so an image is recognised even when MuPDF's format
     * string is unusual.
     */
    private val imageExtensions = setOf(
        "bmp", "gif", "jpg", "jpeg", "jpe", "jfif", "png", "tif", "tiff",
        "jp2", "jpx", "j2k", "pnm", "pbm", "pgm", "ppm", "pam", "pfm",
        "psd", "jxr", "wdp", "hdp", "jb2", "jbig2", "webp", "heic", "heif",
        "avif",
    )

    /** MuPDF format names that describe a still image. */
    private val imageKinds = setOf(
        "JPEG", "JPG", "PNG", "GIF", "BMP", "TIFF", "TIF", "JP2", "JPX",
        "J2K", "PNM", "PBM", "PGM", "PPM", "PAM", "PFM", "PSD", "JXR",
        "WDP", "HDP", "JBIG2", "WEBP", "HEIC", "HEIF", "AVIF",
    )

    fun mimeTypeForName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return byExtension[extension]
            ?: TEXT.takeIf { extension in plainTextExtensions }
    }

    fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in imageExtensions

    fun isImageKind(kind: String?): Boolean = kind
        ?.trim()
        ?.uppercase()
        ?.let { it in imageKinds || it.startsWith("JPEG") || it.startsWith("PNG") }
        ?: false

    /** True for every suffix PDFr treats as reflowable plain text. */
    fun isPlainTextName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in plainTextExtensions
    }

    /** True for the text wildcard and the application MIME types that carry text. */
    fun isTextMimeType(mimeType: String?): Boolean {
        val mime = baseMimeType(mimeType) ?: return false
        return mime.startsWith("text/") || mime in applicationTextMimeTypes
    }

    /**
     * Returns the canonical suffix MuPDF understands for [name], or null when
     * the original extension already works. Text suffixes MuPDF does not know
     * (`.nfo`, source and configuration formats) become `.txt` copies; native
     * `.txt`/`.text`/`.log` files open in place. Markdown aliases become `.md`.
     */
    fun muPdfAliasForName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        mupdfAliases[extension]?.let { return it }
        if (extension in plainTextExtensions && extension !in nativeTextExtensions) {
            return "txt"
        }
        return null
    }

    /**
     * Uses a provider's MIME type as a hint when the file name has no useful
     * extension. HTML/XHTML/Markdown keep their real handlers; other text
     * formats are opened as plain text.
     */
    fun muPdfSuffixForMime(mimeType: String?): String? {
        val mime = baseMimeType(mimeType) ?: return null
        return when {
            mime == HTML -> "html"
            mime == XHTML -> "xhtml"
            mime == MARKDOWN || mime == "text/x-markdown" -> "md"
            isTextMimeType(mime) -> "txt"
            else -> null
        }
    }

    fun mimeTypeForFile(file: File): String =
        mimeTypeForName(file.name) ?: "application/octet-stream"

    /**
     * Best-effort detection for files with an unknown suffix. MuPDF's `open`
     * has already failed by the time this is called, so binary magic numbers
     * are rejected and a text file is retried through a `.txt` copy.
     */
    fun looksLikeText(file: File, maxBytes: Int = 64 * 1024): Boolean {
        val sample = ByteArray(maxBytes.coerceAtLeast(1))
        val read = try {
            file.inputStream().use { stream ->
                var total = 0
                while (total < sample.size) {
                    val count = stream.read(sample, total, sample.size - total)
                    if (count < 0) break
                    total += count
                }
                total
            }
        } catch (_: IOException) {
            return false
        }
        if (read <= 0) return true
        if (hasBinarySignature(sample, read)) return false

        // MuPDF's text handler understands UTF-8 and UTF-16 BOMs.
        if (read >= 3 &&
            sample[0] == 0xEF.toByte() &&
            sample[1] == 0xBB.toByte() &&
            sample[2] == 0xBF.toByte()
        ) {
            return true
        }
        if (read >= 2 &&
            ((sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) ||
                    (sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()))
        ) {
            return true
        }

        var controls = 0
        for (index in 0 until read) {
            val byte = sample[index].toInt() and 0xFF
            if (byte == 0) return false
            val allowedControl = byte == 0x09 || byte == 0x0A || byte == 0x0D || byte == 0x0C
            if (byte < 0x20 && !allowedControl) controls++
        }
        return controls * 100 <= read
    }

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

    private fun baseMimeType(mimeType: String?): String? = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    private fun hasBinarySignature(bytes: ByteArray, length: Int): Boolean {
        fun startsWith(vararg values: Int): Boolean {
            if (length < values.size) return false
            return values.indices.all { (bytes[it].toInt() and 0xFF) == values[it] }
        }
        return startsWith(0x25, 0x50, 0x44, 0x46) || // %PDF
                startsWith(0x50, 0x4B, 0x03, 0x04) || // ZIP, EPUB, CBZ
                startsWith(0x89, 0x50, 0x4E, 0x47) || // PNG
                startsWith(0xFF, 0xD8, 0xFF) || // JPEG
                startsWith(0x47, 0x49, 0x46, 0x38) || // GIF8
                startsWith(0x7F, 0x45, 0x4C, 0x46) || // ELF
                startsWith(0x1F, 0x8B) || // gzip
                startsWith(0x42, 0x4D) // BMP
    }
}
