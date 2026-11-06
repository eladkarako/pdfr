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
        assertEquals("text/html", DocumentFormats.mimeTypeForName("page.html"))
        assertEquals("application/x-fictionbook+xml", DocumentFormats.mimeTypeForName("book.fb2"))
    }

    @Test
    fun recognizesPlainTextExtensions() {
        assertTrue(DocumentFormats.isPlainTextName("notes.txt"))
        assertTrue(DocumentFormats.isPlainTextName("README.NFO"))
        assertTrue(DocumentFormats.isPlainTextName("book.text"))
        assertFalse(DocumentFormats.isPlainTextName("paper.pdf"))
        assertFalse(DocumentFormats.isPlainTextName("book.epub"))
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
