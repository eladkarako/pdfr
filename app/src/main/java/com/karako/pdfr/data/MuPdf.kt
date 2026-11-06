package com.karako.pdfr.data

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * MuPDF uses a per-thread context and its native objects are not safe to use
 * concurrently. Every MuPDF call in PDFr is therefore made on this one
 * background thread, and the UI only ever sees plain Kotlin data and Bitmaps.
 */
object MuPdf {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdfr-mupdf").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    val dispatcher = executor.asCoroutineDispatcher()

    /** Fire-and-forget cleanup that must outlive a cancelled ViewModel scope. */
    fun execute(block: () -> Unit) {
        executor.execute {
            runCatching(block)
        }
    }
}
