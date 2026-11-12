package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class DocumentMetadataTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private val now = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    @Test
    fun splitsAndCombinesANameAndExtension() {
        assertEquals("book" to "epub", DocumentMetadataDraft.splitName("book.epub"))
        assertEquals("archive.tar" to "gz", DocumentMetadataDraft.splitName("archive.tar.gz"))
        assertEquals("README" to "", DocumentMetadataDraft.splitName("README"))
        assertEquals("book.epub", DocumentMetadataDraft.combineName(" book ", " .epub "))
        assertEquals("README", DocumentMetadataDraft.combineName("README", ""))
    }

    @Test
    fun dotfilesAndTrailingDotsDoNotBecomeExtensions() {
        assertEquals(".profile" to "", DocumentMetadataDraft.splitName(".profile"))
        assertEquals("report." to "", DocumentMetadataDraft.splitName("report."))
        assertEquals("my.report" to "pdf", DocumentMetadataDraft.splitName("my.report.pdf"))
    }

    @Test
    fun theDocumentTimestampWinsOverTheProviderFallback() {
        val info = DocumentInfo(
            fileName = "book.epub",
            sizeBytes = 1,
            pageCount = 1,
            kind = "EPUB",
            fileModified = now,
            fileCreated = now,
        )

        val draft = DocumentMetadataDraft.from(info, lastModified = 0L, timeZone = utc)

        assertEquals("2026-01-01T00:00:00", draft.fileModified)
        assertEquals("2026-01-01T00:00:00", draft.fileCreated)
    }

    @Test
    fun fileTimestampsAreStoredSeparatelyFromDocumentDates() {
        val draft = DocumentMetadataDraft(
            fileName = "book",
            extension = "epub",
            fileCreated = "2026-02-03T04:05:06",
            fileModified = "2026-02-04T05:06:07",
            created = "2020-01-02T03:04:05",
            modified = "",
        )

        val values = draft.fileTimestampValues()

        assertEquals("2026-02-03T04:05:06", values[DocumentMetadataDraft.KEY_FILE_CREATED])
        assertEquals("2026-02-04T05:06:07", values[DocumentMetadataDraft.KEY_FILE_MODIFIED])
        assertEquals(2, values.size)
    }

    @Test
    fun sidecarCarriesTheFileCreationTime() {
        val draft = DocumentMetadataDraft(
            fileName = "book",
            extension = "pdf",
            fileCreated = "2026-02-03T04:05:06",
        )

        val values = draft.sidecarValues(utc)

        assertEquals("2026-02-03T04:05:06", values[DocumentMetadataDraft.KEY_FILE_CREATED])
    }

    @Test
    fun buildsADraftFromExistingInfoOnly() {
        val info = DocumentInfo(
            fileName = "report.pdf",
            sizeBytes = 12,
            pageCount = 3,
            kind = "PDF",
            title = "Old title",
            created = "2026-01-02T03:04:05",
            fileModified = now,
        )

        val draft = DocumentMetadataDraft.from(info, timeZone = utc)

        assertEquals("report", draft.fileName)
        assertEquals("pdf", draft.extension)
        assertEquals("Old title", draft.title)
        assertNull(draft.author)
        assertEquals("2026-01-01T00:00:00", draft.fileModified)
        assertTrue(draft.isValid)
    }

    @Test
    fun emptySidecarValuesRemoveMetadataButMissingKeysFallBack() {
        val info = DocumentInfo(
            fileName = "book.epub",
            sizeBytes = 1,
            pageCount = 1,
            kind = "EPUB",
            title = "Original",
            author = "Writer",
        )

        val overlaid = DocumentMetadataDraft.applySidecar(
            info = info,
            values = mapOf(
                DocumentMetadataDraft.KEY_TITLE to "",
                DocumentMetadataDraft.KEY_AUTHOR to "Someone else",
            ),
            timeZone = utc,
        )

        assertNull(overlaid.title)
        assertEquals("Someone else", overlaid.author)
        assertEquals("book.epub", overlaid.fileName)
    }

    @Test
    fun sidecarKeepsClearedFieldsSoTheyOverrideTheFile() {
        val draft = DocumentMetadataDraft(
            fileName = "book",
            extension = "epub",
            title = "",
            author = "Writer",
        )

        val values = draft.sidecarValues(utc)

        assertEquals("book.epub", values[DocumentMetadataDraft.KEY_DISPLAY_NAME])
        assertEquals("", values[DocumentMetadataDraft.KEY_TITLE])
        assertEquals("Writer", values[DocumentMetadataDraft.KEY_AUTHOR])
        assertFalse(values.containsKey(DocumentMetadataDraft.KEY_SUBJECT))
    }

    @Test
    fun pdfMetadataConvertsDatesAndSkipsAbsentFields() {
        val draft = DocumentMetadataDraft(
            fileName = "report",
            extension = "pdf",
            title = "Title",
            created = "2026-01-02T03:04:05",
        )

        val values = draft.pdfMetadata(utc)

        assertEquals("Title", values["info:Title"])
        assertEquals("D:20260102030405", values["info:CreationDate"])
        assertFalse(values.containsKey("info:Author"))
    }

    @Test
    fun clearingAPdfFieldStillWritesTheEmptyValue() {
        val draft = DocumentMetadataDraft(
            fileName = "report",
            extension = "pdf",
            title = "",
            author = "",
        )

        val values = draft.pdfMetadata(utc)

        assertEquals("", values["info:Title"])
        assertEquals("", values["info:Author"])
    }

    @Test
    fun validatesNamesExtensionsAndDates() {
        assertTrue(DocumentMetadataDraft("book", "pdf").isValid)
        assertFalse(DocumentMetadataDraft("", "pdf").isValid)
        assertFalse(DocumentMetadataDraft("book", "p df").isValid)
        assertFalse(DocumentMetadataDraft("book", "toolongex").isValid)
        assertFalse(
            DocumentMetadataDraft("book", "pdf", created = "yesterday").isValid,
        )
        assertTrue(
            DocumentMetadataDraft("book", "pdf", created = "").isValid,
        )
    }

    @Test
    fun sidecarRoundTripsAndDeletesWithTheDocument() {
        val dir = createTempDir()
        try {
            val document = File(dir, "book.epub").apply { writeText("content") }

            assertNull(MetadataSidecar.read(document))
            MetadataSidecar.write(
                document,
                mapOf(
                    DocumentMetadataDraft.KEY_DISPLAY_NAME to "book.epub",
                    DocumentMetadataDraft.KEY_TITLE to "T",
                    DocumentMetadataDraft.KEY_SUBJECT to "",
                ),
            )

            val values = MetadataSidecar.read(document)
            assertEquals("T", values?.get(DocumentMetadataDraft.KEY_TITLE))
            assertEquals("", values?.get(DocumentMetadataDraft.KEY_SUBJECT))

            MetadataSidecar.delete(document)
            assertNull(MetadataSidecar.read(document))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "pdfr-meta-${System.nanoTime()}")
            .apply { mkdirs() }
}
