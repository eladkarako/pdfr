package com.karako.pdfr

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karako.pdfr.data.DocumentStore
import com.karako.pdfr.data.MetadataSidecar
import com.karako.pdfr.data.StorageConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * Exercises [DocumentStore] on a real Android filesystem: the import copy,
 * stale-document cleanup, rewrite/share allocation and discard. These paths
 * touch cache directories and ContentResolver, so they cannot run on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class DocumentStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var scratch: File
    private lateinit var documentsRoot: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        scratch = File(context.cacheDir, "pdfr-store-tests-${System.nanoTime()}")
            .apply { mkdirs() }
        documentsRoot = File(context.cacheDir, StorageConfig.CACHE_DIR)
        documentsRoot.deleteRecursively()
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
        documentsRoot.deleteRecursively()
    }

    private fun source(name: String, contents: String = "fixture"): File =
        File(scratch, name).apply { writeText(contents) }

    private fun import(file: File, name: String, lastModified: Long? = null): File =
        runBlocking {
            DocumentStore.import(context, Uri.fromFile(file), name, lastModified)
        }

    @Test
    fun importCopiesTheDocumentIntoTheCache() {
        val source = source("incoming.txt", "hello pdfr")
        val imported = import(source, "My Report.txt", lastModified = 1_700_000_000_000L)

        assertTrue(imported.exists())
        assertEquals("My Report.txt", imported.name)
        assertEquals("hello pdfr", imported.readText())
        assertEquals(1_700_000_000_000L, imported.lastModified())
        assertEquals(documentsRoot, imported.parentFile?.parentFile)
    }

    @Test
    fun importSanitisesTheDisplayName() {
        val imported = import(source("incoming.txt"), "a:b*c?.txt")

        assertEquals("a_b_c_.txt", imported.name)
        assertTrue(imported.exists())
    }

    @Test
    fun importingAgainRemovesThePreviousDocument() {
        val first = import(source("one.txt", "one"), "one.txt")
        val second = import(source("two.txt", "two"), "two.txt")

        assertFalse(first.exists())
        assertFalse("the empty per-import directory is removed", first.parentFile!!.exists())
        assertTrue(second.exists())
        assertEquals("two", second.readText())
        assertEquals(
            "only the new document is left in the cache",
            listOf(second.parentFile),
            documentsRoot.listFiles()?.toList(),
        )
    }

    @Test
    fun importingAnEmptyFileFailsAndLeavesNoDocument() {
        val empty = source("empty.txt", "")
        try {
            import(empty, "empty.txt")
            fail("an empty import must be rejected")
        } catch (expected: IOException) {
            assertTrue(expected.message?.isNotBlank() == true)
        }

        assertTrue(documentsRoot.listFiles().isNullOrEmpty())
    }

    @Test
    fun derivedFilesGetTheirOwnDirectoryAndSafeName() {
        val first = DocumentStore.derivedFile(context, "fixed.pdf")
        val second = DocumentStore.derivedFile(context, "fixed.pdf")

        assertEquals("fixed.pdf", first.name)
        assertEquals("fixed.pdf", second.name)
        assertNotEquals(first.parentFile, second.parentFile)
        assertEquals(documentsRoot, first.parentFile?.parentFile)
        assertTrue(first.parentFile!!.exists())
        assertTrue(second.parentFile!!.exists())
    }

    @Test
    fun shareCopyUsesTheEditedNameAndClearsOldShareDirectories() {
        val document = import(source("incoming.txt", "shared"), "incoming.txt")

        val firstShare = DocumentStore.shareCopy(context, document, "Edited Name.txt")
        assertEquals("Edited Name.txt", firstShare.name)
        assertEquals("shared", firstShare.readText())
        assertTrue(firstShare.parentFile!!.name.startsWith(StorageConfig.SHARE_DIR_PREFIX))

        val secondShare = DocumentStore.shareCopy(context, document, "Another.txt")
        assertFalse("the previous share directory is cleared", firstShare.parentFile!!.exists())
        assertTrue(secondShare.exists())
        assertTrue("the source document survives sharing", document.exists())
    }

    @Test
    fun discardRemovesTheDocumentAndItsSidecarButKeepsTheRoot() {
        val document = import(source("incoming.txt"), "incoming.txt")
        MetadataSidecar.write(document, mapOf("title" to "T"))
        val sidecar = MetadataSidecar.fileFor(document)
        assertTrue(sidecar.exists())

        DocumentStore.discard(document)

        assertFalse(document.exists())
        assertFalse(sidecar.exists())
        assertFalse("the per-import directory is removed", document.parentFile!!.exists())
        assertTrue("the cache root stays for the next import", documentsRoot.exists())
    }
}
