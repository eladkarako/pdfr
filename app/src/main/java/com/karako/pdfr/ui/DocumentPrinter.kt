package com.karako.pdfr.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.karako.pdfr.data.DocumentStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A PDF ready for the print spooler.
 *
 * [file] is the cached document itself unless it is encrypted: the spooler
 * cannot ask for a password, so [ReaderViewModel.printSource] writes a
 * decrypted copy first and marks it [temporary]. Temporary copies are deleted
 * once the spooler has read them.
 */
data class PrintSource(
    val file: File,
    val displayName: String,
    val pageCount: Int,
    val temporary: Boolean = false,
) {
    fun discard() {
        if (temporary) DocumentStore.discard(file)
    }
}

/**
 * Sends an already decoded PDF to the system print service.
 *
 * The spooler has its own PDF renderer, so the adapter only hands it the bytes.
 * Pagination and preview then match the document exactly without rasterising
 * pages in the app.
 */
object DocumentPrinter {

    /**
     * Opens the system print dialog for [source]. Returns false when the device
     * has no print service; the caller can then show an error.
     */
    fun print(activity: Activity, source: PrintSource): Boolean {
        val manager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (manager == null) {
            source.discard()
            return false
        }

        val adapter = PdfFileAdapter(source)
        return try {
            manager.print(
                source.displayName,
                adapter,
                PrintAttributes.Builder().build(),
            )
            true
        } catch (e: RuntimeException) {
            source.discard()
            false
        }
    }

    private class PdfFileAdapter(
        private val source: PrintSource,
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }

            val info = PrintDocumentInfo.Builder(source.displayName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(source.pageCount.coerceAtLeast(1))
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                // The spooler may ask for a subset of pages, but it renders the
                // PDF itself, so handing it the whole file is both correct and
                // simpler than slicing it here.
                FileInputStream(source.file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                }
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                } else {
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }

        override fun onFinish() {
            // The spooler has copied the bytes by now; a decrypted temporary
            // file should not outlive the job.
            source.discard()
        }
    }
}
