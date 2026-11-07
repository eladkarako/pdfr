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
        assertFalse(DocumentFormats.isPlainTextName("paper.pdf"))
        assertFalse(DocumentFormats.isPlainTextName("book.epub"))
    }

    @Test
    fun aliasesSuffixesMuPdfDoesNotRecognise() {
        assertEquals("txt", DocumentFormats.muPdfAliasForName("README.nfo"))
        assertEquals("md", DocumentFormats.muPdfAliasForName("guide.markdown"))
        assertEquals("md", DocumentFormats.muPdfAliasForName("guide.mdown"))
        assertEquals("md", DocumentFormats.muPdfAliasForName("guide.mkd"))
        assertEquals("md", DocumentFormats.muPdfAliasForName("guide.mkdn"))
        assertNull(DocumentFormats.muPdfAliasForName("guide.md"))
        assertNull(DocumentFormats.muPdfAliasForName("notes.txt"))
        assertNull(DocumentFormats.muPdfAliasForName("paper.pdf"))
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
