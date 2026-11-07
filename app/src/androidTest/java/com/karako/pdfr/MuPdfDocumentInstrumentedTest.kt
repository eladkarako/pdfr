package com.karako.pdfr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFPage
import com.karako.pdfr.data.DocumentPasswordRequiredException
import com.karako.pdfr.data.DocumentWrongPasswordException
import com.karako.pdfr.data.MuPdf
import com.karako.pdfr.data.MuPdfDocument
import com.karako.pdfr.data.Qpdf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exercises the real native MuPDF library on the device: opening, layout,
 * rendering, text selection, and the reorder/rotate/compact/linearize rewrites.
 *
 * All MuPDF work is dispatched to [MuPdf.dispatcher], exactly like the app.
 */
@RunWith(AndroidJUnit4::class)
class MuPdfDocumentInstrumentedTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "pdfr-tests-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun <T> onMuPdf(block: () -> T): T = runBlocking(MuPdf.dispatcher) { block() }

    private fun pdf(name: String, pages: Int): File {
        val file = File(dir, name)
        TestPdfWriter.write(file, (1..pages).map { "Fixture page $it" })
        return file
    }

    private fun open(file: File, password: String? = null): MuPdfDocument =
        onMuPdf { MuPdfDocument.open(file, file.name, password) }

    /** Writes a minimal OOXML Word document, enough for MuPDF's Office handler. */
    private fun docx(name: String, paragraphs: List<String>): File {
        fun escape(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        val body = paragraphs.joinToString("\n") {
            "<w:p><w:r><w:t xml:space=\"preserve\">${escape(it)}</w:t></w:r></w:p>"
        }
        val document = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>$body</w:body>
            </w:document>
        """.trimIndent()
        val rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent()
        val documentRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>
        """.trimIndent()

        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
            """.trimIndent())
            add("_rels/.rels", rootRels)
            add("word/document.xml", document)
            add("word/_rels/document.xml.rels", documentRels)
        }
        return file
    }

    private fun close(doc: MuPdfDocument) {
        onMuPdf { doc.close() }
    }

    private fun textOf(doc: MuPdfDocument, page: Int): String =
        onMuPdf { doc.selectAll(page)?.text.orEmpty() }

    @Test
    fun opensPdfAndReportsInfo() {
        val file = pdf("basic.pdf", 3)
        val doc = open(file)
        try {
            assertEquals(3, onMuPdf { doc.countPages() })

            val info = onMuPdf { doc.info() }
            assertTrue(info.isPdf)
            assertEquals("PDF", info.kind)
            assertEquals(file.name, info.fileName)
            assertFalse(info.encrypted)
            assertFalse(doc.isReflowable)

            val bounds = onMuPdf { doc.pageBounds(0) }
            assertNotNull(bounds)
            assertTrue(bounds!!.width > 0f)
            assertTrue(bounds.height > 0f)
        } finally {
            close(doc)
        }
    }

    @Test
    fun rendersPageAtRequestedWidth() {
        val doc = open(pdf("render.pdf", 1))
        try {
            val rendered = onMuPdf { doc.renderPage(0, 400) }
            assertNotNull(rendered)
            assertTrue(rendered!!.bitmap.width in 1..400)
            assertTrue(rendered.bitmap.height > 0)
            assertFalse(rendered.bitmap.isRecycled)
        } finally {
            close(doc)
        }
    }

    @Test
    fun rendersFixedLayoutPageProportionallyAtZoom() {
        val doc = open(pdf("render-zoom.pdf", 1))
        try {
            val normal = onMuPdf { doc.renderPage(0, 400) }
            val zoomed = onMuPdf { doc.renderPage(0, 800) }
            assertNotNull(normal)
            assertNotNull(zoomed)

            val normalWidth = normal!!.bitmap.width
            val zoomedWidth = zoomed!!.bitmap.width
            // A fixed-layout page is rasterised at the requested zoom, so the
            // wider request produces a proportionally larger bitmap.
            assertEquals(2.0, zoomedWidth.toDouble() / normalWidth, 0.05)
            assertEquals(
                2.0,
                zoomed.bitmap.height.toDouble() / normal.bitmap.height,
                0.05,
            )
        } finally {
            close(doc)
        }
    }

    @Test
    fun reflowAtHigherZoomRepaginatesWithoutLosingText() {
        val file = File(dir, "zoom-notes.txt").apply {
            writeText(
                (1..120).joinToString("\n\n") {
                    "Paragraph $it: the quick brown fox jumps over the lazy dog " +
                        "while a second sentence makes the line wrap more than once."
                },
            )
        }

        val doc = open(file)
        try {
            assertTrue(doc.isReflowable)

            // 100%: the reader lays the page out at the full viewport width
            // and height. 200% halves both values, which is what a narrow
            // reflow does; more text then has to move to further pages.
            assertTrue(onMuPdf { doc.layout(400f, 800f, 11f) })
            val normalPages = onMuPdf { doc.countPages() }
            assertTrue(normalPages >= 1)
            assertTrue(textOf(doc, 0).contains("Paragraph 1"))

            assertTrue(onMuPdf { doc.layout(200f, 400f, 11f) })
            val zoomedPages = onMuPdf { doc.countPages() }
            assertTrue(
                "zoomed layout had $zoomedPages pages, normal had $normalPages",
                zoomedPages > normalPages,
            )
            assertTrue(textOf(doc, 0).contains("Paragraph 1"))
            val tail = buildString {
                if (zoomedPages >= 2) append(textOf(doc, zoomedPages - 2))
                append(textOf(doc, zoomedPages - 1))
            }
            assertTrue(
                "last paragraph missing after zoom reflow: '${tail.takeLast(300)}'",
                tail.contains("Paragraph 120"),
            )

            val rendered = onMuPdf { doc.renderPage(0, 200) }
            assertNotNull(rendered)
            assertTrue(rendered!!.bitmap.width > 0)
        } finally {
            close(doc)
        }
    }

    @Test
    fun selectsAllTextOnAPage() {
        val doc = open(pdf("select.pdf", 1))
        try {
            val selection = onMuPdf { doc.selectAll(0) }
            assertNotNull(selection)
            assertTrue(selection!!.text.contains("Fixture page 1"))
            assertTrue(selection.quads.isNotEmpty())
        } finally {
            close(doc)
        }
    }

    @Test
    fun reorderKeepsRequestedOrderAndDuplicates() {
        val source = pdf("reorder-source.pdf", 4)
        val target = File(dir, "reordered.pdf")

        assertTrue(
            onMuPdf {
                MuPdfDocument.savePdfCopy(
                    source = source,
                    password = null,
                    destination = target,
                    pageIndices = intArrayOf(2, 0, 0, 3),
                    options = MuPdfDocument.PDF_COMPACT_OPTIONS,
                )
            },
        )

        val doc = open(target)
        try {
            assertEquals(4, onMuPdf { doc.countPages() })
            listOf(3, 1, 1, 4).forEachIndexed { index, expected ->
                val text = textOf(doc, index)
                assertTrue(
                    "page $index should contain 'Fixture page $expected' but was: $text",
                    text.contains("Fixture page $expected"),
                )
            }
        } finally {
            close(doc)
        }
    }

    @Test
    fun compactKeepsAllPages() {
        val source = pdf("compact-source.pdf", 5)
        val target = File(dir, "compact.pdf")

        assertTrue(
            onMuPdf {
                MuPdfDocument.savePdfCopy(
                    source = source,
                    password = null,
                    destination = target,
                    pageIndices = null,
                    options = MuPdfDocument.PDF_COMPACT_OPTIONS,
                )
            },
        )
        assertTrue(target.length() > 0L)

        val doc = open(target)
        try {
            assertEquals(5, onMuPdf { doc.countPages() })
            assertTrue(textOf(doc, 4).contains("Fixture page 5"))
        } finally {
            close(doc)
        }
    }

    @Test
    fun rotate180TurnsOnlyListedPages() {
        val source = pdf("rotate-source.pdf", 4)
        val target = File(dir, "rotated.pdf")

        assertTrue(
            onMuPdf {
                MuPdfDocument.savePdfCopy(
                    source = source,
                    password = null,
                    destination = target,
                    pageIndices = null,
                    rotatePages = intArrayOf(0, 2),
                    options = MuPdfDocument.PDF_COMPACT_OPTIONS,
                )
            },
        )

        assertEquals(180, rotationOf(target, 0))
        assertEquals(0, rotationOf(target, 1))
        assertEquals(180, rotationOf(target, 2))
        assertEquals(0, rotationOf(target, 3))
    }

    @Test
    fun qpdfLinearizesPdf() {
        val source = pdf("linearize-source.pdf", 3)
        val target = File(dir, "linearized.pdf")

        assertTrue(Qpdf.isAvailable())
        val error = runBlocking(Qpdf.dispatcher) {
            Qpdf.linearize(source, target, null)
        }
        assertNull(error)
        assertTrue(target.length() > 0L)

        // qpdf's linearized layout starts with a dictionary containing
        // /Linearized near the top of the file.
        val header = String(target.readBytes(), Charsets.ISO_8859_1)
        assertTrue(header.take(8192).contains("/Linearized"))

        val doc = open(target)
        try {
            assertEquals(3, onMuPdf { doc.countPages() })
            assertTrue(textOf(doc, 2).contains("Fixture page 3"))
        } finally {
            close(doc)
        }
    }

    @Test
    fun rejectsOutOfRangeReorderPages() {
        val source = pdf("bad-reorder.pdf", 3)
        val target = File(dir, "bad-reorder-out.pdf")

        assertFalse(
            onMuPdf {
                MuPdfDocument.savePdfCopy(
                    source = source,
                    password = null,
                    destination = target,
                    pageIndices = intArrayOf(0, 3),
                    options = MuPdfDocument.PDF_COMPACT_OPTIONS,
                )
            },
        )
        assertFalse(target.exists())
    }

    @Test
    fun encryptedPdfRequiresTheRightPassword() {
        val plain = pdf("plain-to-encrypt.pdf", 2)
        val encrypted = File(dir, "encrypted.pdf")

        assertTrue(onMuPdf { encryptWithMuPdf(plain, encrypted, PASSWORD) })

        assertThrows(DocumentPasswordRequiredException::class.java) {
            open(encrypted)
        }
        assertThrows(DocumentWrongPasswordException::class.java) {
            open(encrypted, "not-the-password")
        }

        val doc = open(encrypted, PASSWORD)
        try {
            assertEquals(2, onMuPdf { doc.countPages() })
            assertTrue(onMuPdf { doc.info().encrypted })
        } finally {
            close(doc)
        }
    }

    @Test
    fun rewriteOfEncryptedPdfIsUnencrypted() {
        val plain = pdf("plain-rewrite.pdf", 3)
        val encrypted = File(dir, "encrypted-source.pdf")
        val rewritten = File(dir, "rewritten.pdf")

        assertTrue(onMuPdf { encryptWithMuPdf(plain, encrypted, PASSWORD) })
        assertTrue(
            onMuPdf {
                MuPdfDocument.savePdfCopy(
                    source = encrypted,
                    password = PASSWORD,
                    destination = rewritten,
                    pageIndices = intArrayOf(1),
                    options = MuPdfDocument.PDF_COMPACT_OPTIONS,
                )
            },
        )

        // No password: a successful open proves the rewrite removed encryption.
        val doc = open(rewritten)
        try {
            assertEquals(1, onMuPdf { doc.countPages() })
            assertFalse(onMuPdf { doc.info().encrypted })
            assertTrue(textOf(doc, 0).contains("Fixture page 2"))
        } finally {
            close(doc)
        }
    }

    @Test
    fun plainTextReflowsAndTogglesMonospace() {
        val file = File(dir, "notes.txt").apply {
            writeText(
                """
                PDFr plain text fixture
                =======================

                The quick brown fox jumps over the lazy dog.
                Pack my box with five dozen liquor jugs.

                A second paragraph with enough words to wrap across a line.
                """.trimIndent(),
            )
        }

        val doc = open(file)
        try {
            assertTrue(doc.isReflowable)
            assertTrue(doc.isPlainText)
            assertTrue(onMuPdf { doc.layout(400f, 800f, 11f) })
            assertTrue(onMuPdf { doc.countPages() } >= 1)

            val rendered = onMuPdf { doc.renderPage(0, 400) }
            assertNotNull(rendered)
            assertTrue(rendered!!.bitmap.width > 0)

            // Turning the override off switches <pre> to a proportional font.
            assertTrue(onMuPdf { doc.applyMonospace(false) })
            assertTrue(onMuPdf { doc.layout(400f, 800f, 11f) })
            assertTrue(textOf(doc, 0).contains("quick brown fox"))

            // And back on again.
            assertTrue(onMuPdf { doc.applyMonospace(true) })
            assertTrue(onMuPdf { doc.layout(400f, 800f, 11f) })
        } finally {
            close(doc)
        }
    }

    @Test
    fun nfoAndTextExtensionsArePlainText() {
        for (extension in listOf("nfo", "text", "txt", "log")) {
            val file = File(dir, "readme.$extension").apply {
                writeText("PDFr $extension fixture\n\nThe quick brown fox jumps over the lazy dog.\n")
            }
            val doc = open(file)
            try {
                assertTrue(".$extension should reflow", doc.isReflowable)
                assertTrue(".$extension should be plain text", doc.isPlainText)
                assertTrue(onMuPdf { doc.layout(400f, 800f, 11f) })
                assertTrue(textOf(doc, 0).contains("quick brown fox"))
            } finally {
                close(doc)
            }
        }
    }

    @Test
    fun sourceAndConfigFilesOpenAsPlainText() {
        val samples = mapOf(
            "cfg" to "key=value\nsecond=line",
            "ini" to "[section]\nkey=value",
            "yaml" to "key: value\nlist:\n  - one",
            "yml" to "key: value",
            "json" to "{\"key\":\"value\"}",
            "csv" to "name,value\nalpha,1",
            "tsv" to "name\tvalue\nalpha\t1",
            "c" to "int main(void) { return 0; }",
            "cpp" to "#include <iostream>\nint main() { return 0; }",
            "js" to "console.log('hello');",
            "sh" to "#!/bin/sh\necho hello",
            "xml" to "<root><item>value</item></root>",
            // No MuPDF handler and no known extension: the fallback has to
            // detect the bytes as text and retry through a .txt copy.
            "xyz" to "unknown suffix but still plain text",
        )

        for ((extension, body) in samples) {
            val file = File(dir, "sample.$extension").apply { writeText(body) }
            val doc = open(file)
            try {
                assertTrue(".$extension should reflow", doc.isReflowable)
                assertTrue(".$extension should be plain text", doc.isPlainText)
                assertTrue(".$extension layout failed", onMuPdf { doc.layout(400f, 800f, 11f) })
                val text = textOf(doc, 0)
                assertTrue(".$extension produced no text: '$text'", text.isNotBlank())
            } finally {
                close(doc)
            }
        }
    }

    @Test
    fun markdownOpensAndReflowsIncludingLongSuffixes() {
        // .markdown/.mdown/.mkd/.mkdn are not MuPDF extensions, so the reader
        // copies them to a temporary .md before opening.
        for (extension in listOf("md", "markdown", "mdown", "mkd", "mkdn")) {
            val file = File(dir, "guide.$extension").apply {
                writeText(
                    """
                    # PDFr Markdown fixture

                    A paragraph with **bold text** and a [link](https://mupdf.com/).

                    - first item
                    - second item with `inline code`
                    """.trimIndent(),
                )
            }
            val doc = open(file)
            try {
                assertTrue(".$extension should reflow", doc.isReflowable)
                assertTrue(onMuPdf { doc.layout(400f, 800f, 11f) })
                assertTrue(onMuPdf { doc.countPages() } >= 1)
                assertTrue(
                    ".$extension text was: ${textOf(doc, 0)}",
                    textOf(doc, 0).contains("PDFr Markdown fixture"),
                )
            } finally {
                close(doc)
            }
        }
    }

    @Test
    fun docxOpensAsReflowableHtml() {
        val file = docx(
            "sample.docx",
            listOf(
                "PDFr DOCX fixture",
                "MuPDF renders this WordprocessingML document as reflowable HTML.",
                "A second paragraph checks paragraph spacing.",
            ),
        )
        val doc = open(file)
        try {
            assertTrue(doc.isReflowable)
            assertTrue(onMuPdf { doc.layout(400f, 800f, 11f) })
            assertTrue(onMuPdf { doc.countPages() } >= 1)
            val text = textOf(doc, 0)
            assertTrue("DOCX text was: $text", text.contains("PDFr DOCX fixture"))
        } finally {
            close(doc)
        }
    }

    @Test
    fun imageOpensAsSinglePageDocument() {
        val file = File(dir, "image.png")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(40, 110, 200))
            file.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }

        val doc = open(file)
        try {
            assertEquals(1, onMuPdf { doc.countPages() })
            assertFalse(doc.isReflowable)
            val rendered = onMuPdf { doc.renderPage(0, 400) }
            assertNotNull(rendered)
            assertTrue(rendered!!.bitmap.width > 0)
        } finally {
            close(doc)
        }
    }

    @Test
    fun monospaceToggleIsRejectedForPdf() {
        val doc = open(pdf("no-monospace.pdf", 1))
        try {
            assertFalse(doc.isPlainText)
            assertFalse(onMuPdf { doc.applyMonospace(false) })
        } finally {
            close(doc)
        }
    }

    /**
     * Reads the effective `/Rotate` value without going through
     * [MuPdfDocument], which intentionally keeps the reader API high level.
     */
    private fun rotationOf(file: File, index: Int): Int = onMuPdf {
        Context.init()
        var document: Document? = null
        var pdf: com.artifex.mupdf.fitz.PDFDocument? = null
        try {
            document = Document.openDocument(file.absolutePath)
            pdf = document.asPDF() ?: return@onMuPdf 0
            val page = pdf.loadPage(index) as? PDFPage ?: return@onMuPdf 0
            try {
                val value = page.getObject().getInheritable("Rotate")
                if (value != null && value.isInteger) value.asInteger() else 0
            } finally {
                runCatching { page.destroy() }
            }
        } finally {
            runCatching { pdf?.destroy() }
            runCatching { document?.destroy() }
        }
    }

    /**
     * Re-saves [source] with MuPDF's AES-256 encryption. Must run on the MuPDF
     * dispatcher because it creates native objects.
     */
    private fun encryptWithMuPdf(source: File, target: File, password: String): Boolean {
        Context.init()

        var document: Document? = null
        var pdf: com.artifex.mupdf.fitz.PDFDocument? = null
        return try {
            document = Document.openDocument(source.absolutePath)
            pdf = document.asPDF() ?: return false
            pdf.save(
                target.absolutePath,
                "encrypt=aes-256,user-password=$password,owner-password=$password",
            )
            target.length() > 0L
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { pdf?.destroy() }
            runCatching { document?.destroy() }
        }
    }

    private companion object {
        const val PASSWORD = "pdfr-test"
    }
}
