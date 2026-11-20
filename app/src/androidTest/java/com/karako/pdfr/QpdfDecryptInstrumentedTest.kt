package com.karako.pdfr

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.karako.pdfr.data.MuPdf
import com.karako.pdfr.data.MuPdfDocument
import com.karako.pdfr.data.Qpdf
import com.karako.pdfr.ui.ReaderState
import com.karako.pdfr.ui.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Exercises qpdf's decrypt/restriction removal directly and through the
 * reader's normal open path.
 *
 * Decryption is a document operation: opening an encrypted PDF stores an
 * unencrypted cache copy, so later rewrites, Save as and Share never need the
 * password again. Nothing here depends on a viewer button.
 */
@RunWith(AndroidJUnit4::class)
class QpdfDecryptInstrumentedTest {

    private lateinit var app: Application
    private lateinit var dir: File

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
        dir = File(app.cacheDir, "qpdf-decrypt-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun decryptProducesAnUnencryptedCopy() = runBlocking {
        val encrypted = encryptedFixture("direct.pdf")

        val target = File(dir, "decrypted.pdf")
        val error = withContext(Qpdf.dispatcher) {
            Qpdf.decrypt(encrypted, target, PASSWORD)
        }

        assertNull(error)
        assertTrue(target.length() > 0L)

        val doc = withContext(MuPdf.dispatcher) {
            MuPdfDocument.open(target, "decrypted.pdf", password = null)
        }
        try {
            assertEquals(2, doc.countPages())
            assertFalse(doc.info().encrypted)
            assertTrue(doc.selectAll(0)?.text.orEmpty().contains("Secret page one"))
        } finally {
            withContext(MuPdf.dispatcher) { doc.close() }
        }
    }

    @Test
    fun openingAnEncryptedPdfUsesADecryptedCacheCopy() = runBlocking {
        val encrypted = encryptedFixture("opened.pdf")

        val viewModel = ReaderViewModel(app, SavedStateHandle())
        viewModel.open(Uri.fromFile(encrypted))

        val locked = withTimeout(30_000) {
            viewModel.state.first { it is ReaderState.Locked || it is ReaderState.Error }
        }
        assertTrue("expected Locked but was $locked", locked is ReaderState.Locked)

        viewModel.unlock(PASSWORD)
        val readyState = withTimeout(30_000) {
            viewModel.state.first { it is ReaderState.Ready || it is ReaderState.Error }
        }
        assertTrue("expected Ready but was $readyState", readyState is ReaderState.Ready)

        val ready = readyState as ReaderState.Ready
        assertFalse("the cached copy must be decrypted", ready.info.encrypted)
        assertNotEquals(encrypted.absolutePath, ready.file.absolutePath)

        val doc = withContext(MuPdf.dispatcher) {
            MuPdfDocument.open(ready.file, ready.displayName, password = null)
        }
        try {
            assertEquals(2, doc.countPages())
            assertFalse(doc.info().encrypted)
        } finally {
            withContext(MuPdf.dispatcher) { doc.close() }
        }
    }

    private suspend fun encryptedFixture(name: String): File {
        val plain = File(dir, "plain-$name")
        TestPdfWriter.write(plain, listOf("Secret page one", "Secret page two"))
        val encrypted = File(dir, name)
        val encryptedOk = withContext(MuPdf.dispatcher) {
            encryptWithMuPdf(plain, encrypted, PASSWORD)
        }
        assertTrue("MuPDF could not encrypt the fixture", encryptedOk)
        return encrypted
    }

    /** Re-saves [source] with AES-256 encryption. Must run on the MuPDF thread. */
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
