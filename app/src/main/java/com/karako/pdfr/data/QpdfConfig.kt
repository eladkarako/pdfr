package com.karako.pdfr.data

/**
 * Technical constants for the qpdf bridge.
 *
 * These values describe how PDFr loads and drives the native library rather
 * than what the user sees, so they live in Kotlin instead of a string
 * resource. The matching native option names live in `cpp/qpdf_config.h`.
 */
object QpdfConfig {

    /** Name of the shared library built by `tools/build-qpdf-android.ps1`. */
    const val LIBRARY_NAME = "qpdfjni"

    /** Name of the serialized worker thread, visible in thread dumps. */
    const val THREAD_NAME = "pdfr-qpdf"

    /** Suffix of the temporary file used while a rewrite is in progress. */
    const val TEMP_SUFFIX = ".part"
}

/**
 * Internal qpdf diagnostics.
 *
 * [Qpdf] returns these to the view model, which turns them into the localized
 * messages in `strings.xml`. They are deliberately not user-facing text: the
 * reader only needs a stable value it can compare, and a failed qpdf run is
 * always reported through [QpdfMessages.NOT_AVAILABLE] or a generic error.
 */
object QpdfMessages {

    /** Returned when the native library could not be loaded. */
    const val NOT_AVAILABLE = "qpdf is not available in this build."

    /** The requested source file does not exist or is empty. */
    const val SOURCE_MISSING = "The source PDF is missing or empty."

    /** qpdf finished without producing any bytes. */
    const val EMPTY_OUTPUT = "qpdf produced an empty file."

    /** The previous copy exists and the platform refused to delete it. */
    const val REPLACE_FAILED = "Couldn't replace the previous copy."

    /** Fallback when Normalize fails without a native message. */
    const val NORMALIZE_FAILED = "qpdf could not normalize this PDF."

    /** Fallback when Fix fails without a native message. */
    const val FIX_FAILED = "qpdf could not fix this PDF."
}
