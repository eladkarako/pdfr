package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocumentFormatsTest {

    @Test
    fun mapsKnownExtensions() {
        assertEquals(DocumentFormats.PDF, DocumentFormats.mimeTypeForName("paper.pdf"))
        assertEquals(DocumentFormats.EPUB, DocumentFormats.mimeTypeForName("book.epub"))
        assertEquals(DocumentFormats.XPS, DocumentFormats.mimeTypeForName("fixed.xps"))
        assertEquals(DocumentFormats.OXPS, DocumentFormats.mimeTypeForName("fixed.oxps"))
        assertEquals(DocumentFormats.CBZ, DocumentFormats.mimeTypeForName("comic.cbz"))
        assertEquals("text/plain", DocumentFormats.mimeTypeForName("notes.txt"))
        assertEquals("text/plain", DocumentFormats.mimeTypeForName("readme.nfo"))
        assertEquals("text/plain", DocumentFormats.mimeTypeForName("book.text"))
        assertEquals("text/plain", DocumentFormats.mimeTypeForName("server.log"))
        assertEquals("text/html", DocumentFormats.mimeTypeForName("page.html"))
        assertEquals("application/xhtml+xml", DocumentFormats.mimeTypeForName("page.xhtml"))
        assertEquals("application/x-fictionbook+xml", DocumentFormats.mimeTypeForName("book.fb2"))
        assertEquals(DocumentFormats.CBT, DocumentFormats.mimeTypeForName("comic.cbt"))
        assertEquals(DocumentFormats.MOBI, DocumentFormats.mimeTypeForName("book.mobi"))
        assertEquals(DocumentFormats.MOBI, DocumentFormats.mimeTypeForName("book.prc"))
        assertEquals(DocumentFormats.MARKDOWN, DocumentFormats.mimeTypeForName("guide.md"))
        assertEquals(DocumentFormats.SVG, DocumentFormats.mimeTypeForName("drawing.svg"))
    }

    @Test
    fun mapsTextSourceAndConfigExtensions() {
        assertEquals(DocumentFormats.TEXT, DocumentFormats.mimeTypeForName("settings.cfg"))
        assertEquals(DocumentFormats.TEXT, DocumentFormats.mimeTypeForName("app.ini"))
        assertEquals(DocumentFormats.TEXT, DocumentFormats.mimeTypeForName("main.c"))
        assertEquals(DocumentFormats.TEXT, DocumentFormats.mimeTypeForName("main.cpp"))
        assertEquals(DocumentFormats.TEXT, DocumentFormats.mimeTypeForName("Main.java"))
        assertEquals(DocumentFormats.JSON, DocumentFormats.mimeTypeForName("data.json"))
        assertEquals(DocumentFormats.XML, DocumentFormats.mimeTypeForName("layout.xml"))
        assertEquals(DocumentFormats.YAML, DocumentFormats.mimeTypeForName("config.yaml"))
        assertEquals(DocumentFormats.TOML, DocumentFormats.mimeTypeForName("Cargo.toml"))
        assertEquals(DocumentFormats.CSV, DocumentFormats.mimeTypeForName("table.csv"))
        assertEquals(DocumentFormats.TSV, DocumentFormats.mimeTypeForName("table.tsv"))
        assertEquals(DocumentFormats.JAVASCRIPT, DocumentFormats.mimeTypeForName("app.js"))
        assertEquals(DocumentFormats.SHELL, DocumentFormats.mimeTypeForName("build.sh"))
    }

    @Test
    fun mapsOfficeAndImageExtensions() {
        assertEquals(DocumentFormats.DOCX, DocumentFormats.mimeTypeForName("letter.docx"))
        assertEquals(DocumentFormats.XLSX, DocumentFormats.mimeTypeForName("sheet.xlsx"))
        assertEquals(DocumentFormats.PPTX, DocumentFormats.mimeTypeForName("deck.pptx"))
        assertEquals(DocumentFormats.HWPX, DocumentFormats.mimeTypeForName("note.hwpx"))
        assertEquals("image/png", DocumentFormats.mimeTypeForName("image.png"))
        assertEquals("image/jpeg", DocumentFormats.mimeTypeForName("photo.jpeg"))
        assertEquals("image/x-portable-anymap", DocumentFormats.mimeTypeForName("image.pnm"))
        assertEquals("image/x-portable-arbitrarymap", DocumentFormats.mimeTypeForName("image.pam"))
        assertEquals("image/x-portable-pixmap", DocumentFormats.mimeTypeForName("image.ppm"))
    }

    @Test
    fun legacyOfficeFormatsStayUnmapped() {
        // MuPDF has no handler for the old binary Office formats, so PDFr must
        // not claim to open them.
        assertNull(DocumentFormats.mimeTypeForName("letter.doc"))
        assertNull(DocumentFormats.mimeTypeForName("sheet.xls"))
        assertNull(DocumentFormats.mimeTypeForName("deck.ppt"))
    }

    @Test
    fun recognizesPlainTextExtensions() {
        assertTrue(DocumentFormats.isPlainTextName("notes.txt"))
        assertTrue(DocumentFormats.isPlainTextName("README.NFO"))
        assertTrue(DocumentFormats.isPlainTextName("book.text"))
        assertTrue(DocumentFormats.isPlainTextName("server.log"))
        assertTrue(DocumentFormats.isPlainTextName("settings.cfg"))
        assertTrue(DocumentFormats.isPlainTextName("data.json"))
        assertTrue(DocumentFormats.isPlainTextName("config.yaml"))
        assertTrue(DocumentFormats.isPlainTextName("main.cpp"))
        assertTrue(DocumentFormats.isPlainTextName("app.js"))
        assertFalse(DocumentFormats.isPlainTextName("paper.pdf"))
        assertFalse(DocumentFormats.isPlainTextName("book.epub"))
        assertFalse(DocumentFormats.isPlainTextName("page.html"))
    }

    @Test
    fun aliasesSuffixesMuPdfDoesNotRecognise() {
        assertEquals("txt", DocumentFormats.muPdfAliasForName("README.nfo"))
        assertEquals("md", DocumentFormats.muPdfAliasForName("guide.markdown"))
        assertEquals("md", DocumentFormats.muPdfAliasForName("guide.mdown"))
        assertEquals("md", DocumentFormats.muPdfAliasForName("guide.mkd"))
        assertEquals("md", DocumentFormats.muPdfAliasForName("guide.mkdn"))
        assertNull(DocumentFormats.muPdfAliasForName("guide.md"))
        // MuPDF handles these suffixes directly, so no temporary copy is made.
        assertNull(DocumentFormats.muPdfAliasForName("notes.txt"))
        assertNull(DocumentFormats.muPdfAliasForName("server.log"))
        assertNull(DocumentFormats.muPdfAliasForName("paper.pdf"))
        assertNull(DocumentFormats.muPdfAliasForName("page.html"))
        assertNull(DocumentFormats.muPdfAliasForName("drawing.svg"))
    }

    @Test
    fun sourceAndConfigSuffixesBecomePlainTextCopies() {
        assertEquals("txt", DocumentFormats.muPdfAliasForName("settings.cfg"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("app.ini"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("data.json"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("config.yaml"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("Cargo.toml"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("table.csv"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("table.tsv"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("main.c"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("main.cpp"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("app.js"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("build.sh"))
        assertEquals("txt", DocumentFormats.muPdfAliasForName("layout.xml"))
    }

    @Test
    fun recognizesTextMimeTypes() {
        assertTrue(DocumentFormats.isTextMimeType("text/plain"))
        assertTrue(DocumentFormats.isTextMimeType("text/x-c"))
        assertTrue(DocumentFormats.isTextMimeType("text/plain; charset=utf-8"))
        assertTrue(DocumentFormats.isTextMimeType("application/json"))
        assertTrue(DocumentFormats.isTextMimeType("application/x-sh"))
        assertTrue(DocumentFormats.isTextMimeType("application/javascript"))
        assertTrue(DocumentFormats.isTextMimeType("application/x-yaml"))
        assertFalse(DocumentFormats.isTextMimeType("application/pdf"))
        assertFalse(DocumentFormats.isTextMimeType("application/octet-stream"))
        assertFalse(DocumentFormats.isTextMimeType(null))
    }

    @Test
    fun mimeHintsChooseACanonicalSuffix() {
        assertEquals("txt", DocumentFormats.muPdfSuffixForMime("text/plain"))
        assertEquals("txt", DocumentFormats.muPdfSuffixForMime("text/x-c"))
        assertEquals("txt", DocumentFormats.muPdfSuffixForMime("application/json"))
        assertEquals("html", DocumentFormats.muPdfSuffixForMime("text/html"))
        assertEquals("xhtml", DocumentFormats.muPdfSuffixForMime("application/xhtml+xml"))
        assertEquals("md", DocumentFormats.muPdfSuffixForMime("text/markdown"))
        assertNull(DocumentFormats.muPdfSuffixForMime("application/pdf"))
        assertNull(DocumentFormats.muPdfSuffixForMime(null))
    }

    @Test
    fun detectsTextFilesForTheUnknownSuffixFallback() {
        assertTrue(DocumentFormats.looksLikeText(tempFile("hello\nworld\n")))
        assertTrue(DocumentFormats.looksLikeText(tempFile("{\"key\":\"value\"}")))
        assertTrue(DocumentFormats.looksLikeText(tempFile("")))
        assertTrue(
            DocumentFormats.looksLikeText(
                tempFile("\uFEFFhello"),
            ),
        )
        assertFalse(
            DocumentFormats.looksLikeText(
                tempFile(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)),
            ),
        )
        assertFalse(
            DocumentFormats.looksLikeText(
                tempFile(
                    byteArrayOf(
                        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    ),
                ),
            ),
        )
    }

    private fun tempFile(content: String): File =
        tempFile(content.toByteArray(Charsets.UTF_8))

    private fun tempFile(content: ByteArray): File {
        val file = File.createTempFile("pdfr-text-", ".bin")
        file.deleteOnExit()
        file.writeBytes(content)
        return file
    }

    @Test
    fun extensionMatchingIsCaseInsensitive() {
        assertEquals(DocumentFormats.PDF, DocumentFormats.mimeTypeForName("PAPER.PDF"))
        assertEquals("text/plain", DocumentFormats.mimeTypeForName("NOTES.TxT"))
    }

    @Test
    fun unknownNamesHaveNoMimeType() {
        assertNull(DocumentFormats.mimeTypeForName("README"))
        assertNull(DocumentFormats.mimeTypeForName("archive.unknownext"))
    }

    @Test
    fun filesFallBackToOctetStream() {
        assertEquals(
            "application/octet-stream",
            DocumentFormats.mimeTypeForFile(File("no-extension")),
        )
        assertEquals(
            DocumentFormats.PDF,
            DocumentFormats.mimeTypeForFile(File("C:\\tmp\\paper.pdf")),
        )
    }

    @Test
    fun textPlainIsOfferedByThePicker() {
        assertTrue(DocumentFormats.PICKER_MIME_TYPES.contains("text/plain"))
        assertTrue(DocumentFormats.PICKER_MIME_TYPES.contains(DocumentFormats.PDF))
        assertTrue(DocumentFormats.PICKER_MIME_TYPES.contains(DocumentFormats.EPUB))
        assertTrue(DocumentFormats.PICKER_MIME_TYPES.contains(DocumentFormats.MARKDOWN))
        assertTrue(DocumentFormats.PICKER_MIME_TYPES.contains(DocumentFormats.DOCX))
        assertTrue(DocumentFormats.PICKER_MIME_TYPES.contains("image/*"))
    }

    @Test
    fun extensionOfAcceptsShortAlphanumericExtensions() {
        assertEquals("pdf", DocumentFormats.extensionOf("paper.pdf"))
        assertEquals("gz", DocumentFormats.extensionOf("archive.tar.gz"))
        assertEquals("7z", DocumentFormats.extensionOf("archive.7z"))
    }

    @Test
    fun extensionOfRejectsOpaqueProviderIds() {
        assertNull(DocumentFormats.extensionOf("no-extension"))
        assertNull(DocumentFormats.extensionOf("msf:1234"))
        assertNull(DocumentFormats.extensionOf("trailing."))
        assertNull(DocumentFormats.extensionOf("weird.longextension"))
    }
}
