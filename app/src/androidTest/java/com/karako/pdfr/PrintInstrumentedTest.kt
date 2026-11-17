package com.karako.pdfr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karako.pdfr.data.MuPdfDocument
import com.karako.pdfr.ui.PrintSource
import com.karako.pdfr.ui.ReaderState
import com.karako.pdfr.ui.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for the print path, driven through the real view model.
 *
 * Printing is the one action that has to work for every format the reader
 * opens: PDFs go straight to the spooler, encrypted PDFs are decrypted first,
 * and reflowable documents and images are converted to a temporary PDF. These
 * tests follow the same route as the toolbar button.
 */
@RunWith(AndroidJUnit4::class)
class PrintInstrumentedTest {

    private lateinit var app: Application
    private val fixtures = mutableListOf<File>()

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
    }

    @After
    fun tearDown() {
        fixtures.forEach { it.delete() }
    }

    @Test
    fun pdfIsHandedToTheSpoolerUnchanged() = runBlocking {
        val (viewModel, ready) = open("print-sample.pdf") { file ->
            TestPdfWriter.write(file, listOf("Print page one", "Print page two"))
        }

        val source = viewModel.printSource()

        assertNotNull("printSource() returned null for a plain PDF", source)
        source!!
        assertFalse("a PDF must not be copied for printing", source.temporary)
        assertEquals(ready.file, source.file)
        assertEquals(2, source.pageCount)
        assertPdf(source.file)
    }

    @Test
    fun plainTextIsConvertedToATemporaryPdf() = runBlocking {
        val (viewModel, ready) = open("print-notes.txt") { file ->
            file.writeText("PDFr print fixture\nSecond line\nThird line\n")
        }

        val source = viewModel.printSource()

        assertNotNull("printSource() returned null for plain text", source)
        source!!
        assertTrue("a converted document must be temporary", source.temporary)
        assertNotEquals(ready.file, source.file)
        assertTrue(source.file.exists())
        assertPdf(source.file)
        assertTrue("the converted PDF must have at least one page", source.pageCount >= 1)

        // The spooler only needs the file while the job runs.
        source.discard()
        assertFalse("discard() must delete a temporary PDF", source.file.exists())
    }

    @Test
    fun imageIsConvertedToATemporaryPdf() = runBlocking {
        val (viewModel, _) = open("print-image.png") { file ->
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(32, 96, 160))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        val source = viewModel.printSource()

        assertNotNull("printSource() returned null for an image", source)
        source!!
        assertTrue(source.temporary)
        assertPdf(source.file)
        assertEquals(1, source.pageCount)
        source.discard()
        assertFalse(source.file.exists())
    }

    @Test
    fun encryptedPdfIsDecryptedForTheSpooler() = runBlocking {
        val (viewModel, _) = open("print-encrypted.pdf", password = PASSWORD) { file ->
            val plain = File(app.cacheDir, "print-encrypted-plain.pdf")
            fixtures += plain
            TestPdfWriter.write(plain, listOf("Secret page one", "Secret page two"))
            assertTrue(encryptWithMuPdf(plain, file, PASSWORD))
        }

        val source = viewModel.printSource()

        assertNotNull("printSource() returned null for an encrypted PDF", source)
        source!!
        assertTrue("a decrypted copy must be temporary", source.temporary)
        assertPdf(source.file)

        // Opening it without a password proves the copy is really decrypted.
        val document = MuPdfDocument.open(source.file, "decrypted.pdf", password = null)
        try {
            assertEquals(2, document.countPages())
            assertFalse(document.info().encrypted)
        } finally {
            document.close()
        }

        source.discard()
        assertFalse(source.file.exists())
    }

    /**
     * Imports [name] through the view model, exactly like a tap on Open, and
     * waits for the document to be ready. Encrypted fixtures are unlocked with
     * [password].
     */
    private suspend fun open(
        name: String,
        password: String? = null,
        write: (File) -> Unit,
    ): Pair<ReaderViewModel, ReaderState.Ready> {
        val file = File(app.cacheDir, name)
        fixtures += file
        write(file)

        val viewModel = ReaderViewModel(app, SavedStateHandle())
        viewModel.open(Uri.fromFile(file))

        if (password != null) {
            val locked = withTimeout(30_000) {
                viewModel.state.first { it is ReaderState.Locked || it is ReaderState.Error }
            }
            assertTrue("expected Locked but was $locked", locked is ReaderState.Locked)
            viewModel.unlock(password)
        }

        val state = withTimeout(30_000) {
            viewModel.state.first { it is ReaderState.Ready || it is ReaderState.Error }
        }
        assertTrue("expected Ready but was $state", state is ReaderState.Ready)
        return viewModel to (state as ReaderState.Ready)
    }

    private fun assertPdf(file: File) {
        assertTrue("the print source does not exist", file.exists())
        val header = file.inputStream().use { input ->
            val bytes = ByteArray(5)
            input.read(bytes)
            String(bytes, Charsets.ISO_8859_1)
        }
        assertEquals("%PDF-", header)
    }

    private fun encryptWithMuPdf(source: File, target: File, password: String): Boolean {
        com.artifex.mupdf.fitz.Context.init()

        var document: com.artifex.mupdf.fitz.Document? = null
        var pdf: com.artifex.mupdf.fitz.PDFDocument? = null
        return try {
            document = com.artifex.mupdf.fitz.Document.openDocument(source.absolutePath)
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
        const val PASSWORD = "pdfr-print-test"
    }
}
