package com.karako.pdfr.data

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.RectI
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.File

/** Bounds of a page in MuPDF's page coordinate space (72 dpi points). */
data class PageBounds(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
}

data class RenderedPage(
    val bitmap: Bitmap,
    val bounds: PageBounds,
)

data class SelectionQuad(
    val ulX: Float,
    val ulY: Float,
    val urX: Float,
    val urY: Float,
    val llX: Float,
    val llY: Float,
    val lrX: Float,
    val lrY: Float,
)

data class TextSelection(
    val quads: List<SelectionQuad>,
    val text: String,
)

data class SnappedSelection(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

/**
 * A single open MuPDF document.
 *
 * Every method is synchronized and must be called on PDFr's dedicated MuPDF
 * thread (see [MuPdf]). MuPDF's Java bindings use a per-thread context and its
 * native objects are not safe to use concurrently.
 */
class MuPdfDocument private constructor(
    val file: File,
    val displayName: String,
    private val document: Document,
    val needsPasswordToOpen: Boolean,
    /** A temporary `.txt` copy used because MuPDF only recognises that suffix. */
    private val temporaryFile: File? = null,
) : AutoCloseable {

    val isReflowable: Boolean = runCatching { document.isReflowable }.getOrDefault(false)

    /**
     * MuPDF turns plain text into simple HTML. Only these documents get the
     * optional monospace override.
     */
    val isPlainText: Boolean = runCatching {
        DocumentFormats.isPlainTextName(file.name) ||
            document.getMetaData(Document.META_FORMAT)?.equals("Text", ignoreCase = true) == true
    }.getOrDefault(false)

    private val pageBounds = HashMap<Int, PageBounds>()

    private val bitmapCache = object : LruCache<Long, Bitmap>(bitmapCacheBytes()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private val textCache = object : LruCache<Int, StructuredText>(TEXT_CACHE_PAGES) {
        override fun entryRemoved(
            evicted: Boolean,
            key: Int,
            oldValue: StructuredText,
            newValue: StructuredText?,
        ) {
            if (evicted && oldValue !== newValue) runCatching { oldValue.destroy() }
        }
    }

    @Volatile
    private var closed = false

    /** Last plain-text font choice applied through [applyReaderStyle]. */
    private var monospaceText: Boolean? = null

    fun countPages(): Int = synchronized(this) {
        if (closed) return 0
        runCatching { document.countPages() }.getOrDefault(0)
    }

    /** Stable reading-position marker, used across reflow layouts. */
    fun makeBookmark(page: Int): Long = synchronized(this) {
        if (closed) return -1L
        runCatching {
            document.makeBookmark(document.locationFromPageNumber(page.coerceAtLeast(0)))
        }.getOrDefault(-1L)
    }

    fun pageForBookmark(mark: Long): Int? = synchronized(this) {
        if (closed || mark < 0L) return null
        runCatching {
            document.pageNumberFromLocation(document.findBookmark(mark))
        }.getOrNull()?.takeIf { it >= 0 }
    }

    /**
     * Applies a new reflow layout. Only meaningful for reflowable documents
     * (EPUB, FB2, ...). Fixed-layout documents keep their own page sizes.
     */
    fun layout(widthPt: Float, heightPt: Float, em: Float): Boolean = synchronized(this) {
        if (closed || !isReflowable) return false
        return runCatching {
            document.layout(
                widthPt.coerceAtLeast(MIN_LAYOUT_PT),
                heightPt.coerceAtLeast(MIN_LAYOUT_PT),
                em.coerceIn(MIN_EM, MAX_EM),
            )
            clearCaches()
            true
        }.getOrDefault(false)
    }

    /**
     * Applies PDFr's user stylesheet to a reflowable document: zero page and
     * body margins so the text uses the full reader width, plus the preferred
     * font for plain-text files. Call [layout] afterwards to reflow.
     */
    fun applyReaderStyle(monospace: Boolean = false): Boolean = synchronized(this) {
        if (closed || !isReflowable) return false
        monospaceText = monospace
        applyReaderStyleLocked()
    }

    /**
     * Chooses the font family used by a plain-text document. MuPDF turns text
     * into `<pre>` blocks, which are monospace by default; turning the option
     * off applies a proportional font instead. Other reflowable formats are
     * left alone. Call [layout] afterwards to reflow with the new font.
     */
    fun applyMonospace(enabled: Boolean): Boolean = synchronized(this) {
        if (closed || !isReflowable || !isPlainText) return false
        monospaceText = enabled
        applyReaderStyleLocked()
    }

    private fun applyReaderStyleLocked(): Boolean = runCatching {
        val font = when {
            !isPlainText -> ""
            monospaceText == true -> MONOSPACE_CSS
            else -> PROPORTIONAL_CSS
        }
        document.style(true, READER_CSS + font)
        clearCaches()
        true
    }.getOrDefault(false)

    fun pageBounds(index: Int): PageBounds? = synchronized(this) {
        if (closed) return null
        pageBounds[index]?.let { return it }

        val page = runCatching { document.loadPage(index) }.getOrNull() ?: return null
        try {
            val rect = runCatching { page.getBounds() }.getOrNull() ?: return null
            val bounds = PageBounds(rect.x0, rect.y0, rect.x1, rect.y1)
            if (bounds.width <= 0f || bounds.height <= 0f) return null
            pageBounds[index] = bounds
            bounds
        } finally {
            runCatching { page.destroy() }
        }
    }

    /**
     * Renders [index] at [targetWidthPx] pixels wide. The returned bitmap may
     * come from the small LRU cache.
     */
    fun renderPage(
        index: Int,
        targetWidthPx: Int,
        background: Int = Color.WHITE,
    ): RenderedPage? = synchronized(this) {
        if (closed) return null

        val bounds = pageBounds(index) ?: return null
        val width = targetWidthPx.coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_WIDTH)

        // Very tall pages at 400% zoom could otherwise allocate a bitmap
        // large enough to exhaust the heap. Render above screen density but
        // cap the pixel budget and let the Image composable scale the result.
        val aspect = (bounds.height / bounds.width).coerceAtLeast(0.01f)
        val pixelCappedWidth = kotlin.math.sqrt(MAX_RENDER_PIXELS / aspect).toInt()
        val renderWidth = width.coerceAtMost(pixelCappedWidth)
            .coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_WIDTH)

        val key = (index.toLong() shl 32) or renderWidth.toLong()
        bitmapCache.get(key)?.let { cached ->
            return RenderedPage(cached, bounds)
        }

        val renderScale = renderWidth / bounds.width
        if (!renderScale.isFinite() || renderScale <= 0f) return null

        val ctm = Matrix(renderScale, renderScale)
        val ibox = RectI(
            Rect(bounds.x0, bounds.y0, bounds.x1, bounds.y1).transform(ctm),
        )
        val bitmapWidth = ibox.x1 - ibox.x0
        val bitmapHeight = ibox.y1 - ibox.y0
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        if (bitmapWidth > MAX_BITMAP_DIMENSION || bitmapHeight > MAX_BITMAP_DIMENSION) return null

        val page = runCatching { document.loadPage(index) }.getOrNull() ?: return null
        var bitmap: Bitmap? = null
        try {
            bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(background)

            val device = AndroidDrawDevice(bitmap, ibox.x0, ibox.y0)
            try {
                page.run(device, ctm, null)
                device.close()
            } finally {
                runCatching { device.destroy() }
            }

            bitmapCache.put(key, bitmap)
            return RenderedPage(bitmap, bounds)
        } catch (_: Throwable) {
            bitmap?.recycle()
            return null
        } finally {
            runCatching { page.destroy() }
        }
    }

    /** Selects the word at a page-space point. */
    fun snapWord(index: Int, x: Float, y: Float): SnappedSelection? = synchronized(this) {
        val text = structuredText(index) ?: return null

        return runCatching {
            val start = Point(x, y)
            val end = Point(x, y)
            text.snapSelection(start, end, StructuredText.SELECT_WORDS)
            if (!start.x.isFinite() || !start.y.isFinite() ||
                !end.x.isFinite() || !end.y.isFinite()
            ) {
                null
            } else {
                SnappedSelection(start.x, start.y, end.x, end.y)
            }
        }.getOrNull()
    }

    fun select(
        index: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): TextSelection? = synchronized(this) {
        val text = structuredText(index) ?: return null

        return runCatching {
            val start = Point(startX, startY)
            val end = Point(endX, endY)
            val quads = text.highlight(start, end) ?: return null
            val copy = text.copy(start, end).orEmpty()
            TextSelection(quads.map { it.toSelectionQuad() }, copy)
        }.getOrNull()
    }

    fun selectAll(index: Int): TextSelection? = synchronized(this) {
        val bounds = pageBounds(index) ?: return null
        select(index, bounds.x0, bounds.y0, bounds.x1, bounds.y1)
    }

    fun info(): DocumentInfo = synchronized(this) {
        val format = meta(Document.META_FORMAT)
        // MuPDF reports "none" as the encryption method for unencrypted files.
        val encryption = meta(Document.META_ENCRYPTION)
            ?.takeUnless { it.equals("none", ignoreCase = true) }
        val pageCount = runCatching { document.countPages() }.getOrDefault(0)

        DocumentInfo(
            fileName = displayName,
            sizeBytes = file.length(),
            pageCount = pageCount,
            kind = kindOf(format, file.name),
            format = format,
            encrypted = needsPasswordToOpen || encryption != null,
            encryption = encryption,
            title = meta(Document.META_INFO_TITLE),
            author = meta(Document.META_INFO_AUTHOR),
            subject = meta(Document.META_INFO_SUBJECT),
            keywords = meta(Document.META_INFO_KEYWORDS),
            creator = meta(Document.META_INFO_CREATOR),
            producer = meta(Document.META_INFO_PRODUCER),
            created = meta(Document.META_INFO_CREATIONDATE),
            modified = meta(Document.META_INFO_MODIFICATIONDATE),
        )
    }

    /** Drops rendered pages, keeping the document and its text cache alive. */
    fun evictRenderedPages() = synchronized(this) {
        bitmapCache.evictAll()
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            clearCaches()
            runCatching { document.destroy() }
            temporaryFile?.delete()
        }
    }

    private fun structuredText(index: Int): StructuredText? {
        if (closed) return null
        textCache.get(index)?.let { return it }

        val page = runCatching { document.loadPage(index) }.getOrNull() ?: return null
        try {
            val text = runCatching {
                page.toStructuredText("preserve-whitespace")
            }.getOrNull() ?: return null
            textCache.put(index, text)
            return text
        } finally {
            runCatching { page.destroy() }
        }
    }

    private fun clearCaches() {
        bitmapCache.evictAll()
        textCache.evictAll()
        pageBounds.clear()
    }

    private fun meta(key: String): String? = runCatching { document.getMetaData(key) }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    companion object {
        private const val TEXT_CACHE_PAGES = 3
        private const val MIN_LAYOUT_PT = 32f
        private const val MIN_EM = 2f
        private const val MAX_EM = 96f
        private const val MIN_RENDER_WIDTH = 64
        private const val MAX_RENDER_WIDTH = 8_192
        private const val MAX_RENDER_PIXELS = 6_000_000
        private const val MAX_BITMAP_DIMENSION = 16_384

        private const val MONOSPACE_CSS = "pre { font-family: monospace; }"
        private const val PROPORTIONAL_CSS = "pre { font-family: sans-serif; }"

        /**
         * MuPDF's default HTML stylesheet leaves a wide body margin. Reflowable
         * documents should use the whole reader width, so remove page and body
         * margins before laying them out.
         */
        private const val READER_CSS =
            "@page { margin: 0; } html, body { margin: 0; padding: 0; }"

        /**
         * Rewrites the PDF without encryption, with unused objects removed and
         * content streams, fonts and images compressed. MuPDF 1.28 no longer
         * linearizes, so this is PDFr's compact action. True linearization is
         * provided by qpdf (see [Qpdf]).
         */
        const val PDF_COMPACT_OPTIONS =
            "garbage=compact,compress=yes,compress-images=yes,compress-fonts=yes,objstms=yes,encrypt=none"

        /**
         * Saves a reordered, rotated or compacted copy of [source] into
         * [destination].
         *
         * [pageIndices] are zero-based source page numbers. They are passed to
         * MuPDF in exactly the order given, so the reader can duplicate or
         * reorder pages. The rewrite always decrypts the result when written.
         *
         * [rotatePages] are zero-based source pages to turn upside down before
         * saving. They are applied before [pageIndices] is rearranged.
         *
         * Must be called on [MuPdf.dispatcher].
         */
        fun savePdfCopy(
            source: File,
            password: String?,
            destination: File,
            pageIndices: IntArray?,
            rotatePages: IntArray? = null,
            options: String,
        ): Boolean {
            Context.init()

            val temp = File(destination.parentFile, "${destination.name}.part")
            var document: Document? = null
            var pdf: PDFDocument? = null

            return try {
                temp.delete()

                document = Document.openDocument(source.absolutePath)
                if (document.needsPassword() &&
                    !document.authenticatePassword(password.orEmpty())
                ) {
                    return false
                }

                pdf = document.asPDF() ?: return false
                val pageCount = pdf.countPages()

                if (rotatePages != null && rotatePages.isNotEmpty()) {
                    if (rotatePages.any { it < 0 || it >= pageCount }) return false
                    // A page can appear twice in a reorder request; rotating it
                    // twice would be a no-op, so only touch each index once.
                    for (index in rotatePages.toSortedSet()) {
                        val page = runCatching { pdf.loadPage(index) }.getOrNull()
                            ?: return false
                        try {
                            val pdfPage = page as? PDFPage ?: return false
                            val objectHandle = pdfPage.getObject()
                            val current = runCatching {
                                val value = objectHandle.getInheritable("Rotate")
                                if (value != null && value.isInteger) value.asInteger() else 0
                            }.getOrDefault(0)
                            val rotated = ((current + 180) % 360 + 360) % 360
                            objectHandle.put("Rotate", rotated)
                            pdfPage.update()
                        } finally {
                            runCatching { page.destroy() }
                        }
                    }
                }

                if (pageIndices != null) {
                    if (pageIndices.isEmpty()) return false
                    if (pageIndices.any { it < 0 || it >= pageCount }) return false
                    pdf.rearrangePages(pageIndices)
                }

                pdf.save(temp.absolutePath, options)
                if (temp.length() <= 0L) return false

                if (destination.exists() && !destination.delete()) return false
                if (!temp.renameTo(destination)) {
                    temp.copyTo(destination, overwrite = true)
                    temp.delete()
                }

                destination.length() > 0L
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { pdf?.destroy() }
                runCatching { document?.destroy() }
                runCatching { temp.delete() }
            }
        }

        /**
         * Copies [file] next to itself with the canonical [suffix]. The copy is
         * byte-for-byte identical: no byte-order mark is added, so the bytes
         * MuPDF reads match the source file exactly.
         */
        private fun copyWithSuffix(file: File, suffix: String): File {
            val temp = File(file.parentFile, ".${java.util.UUID.randomUUID()}.$suffix")
            return try {
                file.copyTo(temp, overwrite = true)
                temp
            } catch (t: Throwable) {
                runCatching { temp.delete() }
                throw t
            }
        }

        /**
         * Opens [file] with MuPDF. If the document needs a password, [password]
         * must be the user's password; otherwise the appropriate typed
         * exception is thrown.
         */
        fun open(
            file: File,
            displayName: String,
            password: String?,
            mimeType: String? = null,
        ): MuPdfDocument {
            // Touching Context first makes the native library load once, before
            // any document object is created.
            Context.init()

            // MuPDF picks its handler from the file extension. It does not
            // know `.nfo`, the longer Markdown suffixes, or source and
            // configuration formats such as `.json`, `.yaml` or `.cpp`. Its
            // magic-string overload is unusable in MuPDF 1.28.5 (the native
            // method reads the magic as an Archive and aborts), so give those
            // files a temporary copy with the canonical suffix. When the name
            // has no useful extension, a provider MIME type can supply one.
            val knownMime = DocumentFormats.mimeTypeForName(file.name)
            val mimeSuffix = if (knownMime == null) {
                DocumentFormats.muPdfSuffixForMime(mimeType)
            } else {
                null
            }
            val alias = DocumentFormats.muPdfAliasForName(file.name) ?: mimeSuffix
            var temporaryFile = alias?.let { copyWithSuffix(file, it) }

            val document = try {
                Document.openDocument((temporaryFile ?: file).absolutePath)
            } catch (first: Throwable) {
                // A truly unknown suffix may still be a text file. Retry it as
                // `.txt` only after MuPDF failed, and only when the bytes look
                // textual (a corrupt PDF or an image must not become raw text).
                if (temporaryFile == null &&
                    knownMime == null &&
                    DocumentFormats.looksLikeText(file)
                ) {
                    val temp = copyWithSuffix(file, "txt")
                    try {
                        val retried = Document.openDocument(temp.absolutePath)
                        temporaryFile = temp
                        retried
                    } catch (second: Throwable) {
                        runCatching { temp.delete() }
                        first.addSuppressed(second)
                        throw first
                    }
                } else {
                    runCatching { temporaryFile?.delete() }
                    throw first
                }
            }

            var needsPassword = false
            try {
                needsPassword = document.needsPassword()
                if (needsPassword) {
                    // Some documents are encrypted with an empty user password
                    // (permissions-only encryption). Try that before prompting.
                    val accepted = document.authenticatePassword(password.orEmpty())
                    if (!accepted) {
                        if (password == null) throw DocumentPasswordRequiredException()
                        throw DocumentWrongPasswordException()
                    }
                }
            } catch (t: Throwable) {
                runCatching { document.destroy() }
                runCatching { temporaryFile?.delete() }
                throw t
            }

            return MuPdfDocument(file, displayName, document, needsPassword, temporaryFile)
        }

        private fun bitmapCacheBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return (heap / 8L)
                .coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024)
                .toInt()
        }

        private fun kindOf(format: String?, fileName: String): String {
            val value = format?.uppercase().orEmpty()
            return when {
                value.startsWith("PDF") -> "PDF"
                value.contains("EPUB") -> "EPUB"
                value.contains("XPS") -> "XPS"
                value.contains("CBZ") || value.contains("COMIC") -> "CBZ"
                format != null -> format.substringBefore(' ').uppercase()
                else -> DocumentFormats.extensionOf(fileName)?.uppercase() ?: "DOCUMENT"
            }
        }

        private fun Quad.toSelectionQuad() = SelectionQuad(
            ulX = ul_x,
            ulY = ul_y,
            urX = ur_x,
            urY = ur_y,
            llX = ll_x,
            llY = ll_y,
            lrX = lr_x,
            lrY = lr_y,
        )
    }
}
