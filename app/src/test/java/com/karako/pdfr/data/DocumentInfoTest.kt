package com.karako.pdfr.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentInfoTest {

    private fun info(kind: String, format: String? = null) = DocumentInfo(
        fileName = "fixture",
        sizeBytes = 0,
        pageCount = 1,
        kind = kind,
        format = format,
    )

    @Test
    fun pdfKindIsDetected() {
        assertTrue(info("PDF").isPdf)
        assertTrue(info("pdf").isPdf)
    }

    @Test
    fun pdfFormatIsDetected() {
        assertTrue(info("Document", "PDF 1.7").isPdf)
        assertTrue(info("Unknown", "  pdf 2.0 ").isPdf)
    }

    @Test
    fun otherFormatsAreNotPdf() {
        assertFalse(info("EPUB", "EPUB").isPdf)
        assertFalse(info("CBZ", "CBZ").isPdf)
        assertFalse(info("TXT", "Text").isPdf)
        assertFalse(info("DOCUMENT").isPdf)
    }

    @Test
    fun metadataPresenceIsReported() {
        assertFalse(info("PDF").hasMetadata)
        assertTrue(info("PDF").copy(title = "A title").hasMetadata)
        assertTrue(info("PDF").copy(keywords = "one, two").hasMetadata)
    }
}
