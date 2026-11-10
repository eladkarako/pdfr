package com.karako.pdfr.data

import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * qpdf-based PDF operations, kept on their own single background thread.
 *
 * MuPDF owns its own thread because its native context is not safe to share.
 * qpdf is independent of MuPDF, and moving a large file through it should not
 * stall page rendering, so it gets a second serialized dispatcher.
 */
object Qpdf {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdfr-qpdf").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    val dispatcher = executor.asCoroutineDispatcher()

    /** Loaded lazily so a missing native library never blocks the reader. */
    private val loaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("qpdfjni")
            true
        }.getOrDefault(false)
    }

    /** True when the native qpdf bridge is present in this APK. */
    fun isAvailable(): Boolean = loaded

    /**
     * Writes a truly linearized copy of [source] to [destination].
     *
     * Must be called on [dispatcher]. Returns null on success, or a
     * human-readable error. The destination is removed on failure.
     */
    fun linearize(source: File, destination: File, password: String?): String? =
        rewrite(
            source = source,
            destination = destination,
            password = password,
            operation = ::nativeLinearize,
            failure = "qpdf could not linearize this PDF.",
        )

    /**
     * Writes a repaired copy of [source] to [destination].
     *
     * The PDF is written in QDF form, run through the same stream-length and
     * cross-reference repair as qpdf's fix-qdf, then rewritten as a normal
     * PDF. This is qpdf's documented repair cycle for damaged files.
     *
     * Must be called on [dispatcher]. Returns null on success, or a
     * human-readable error. The destination is removed on failure.
     */
    fun fix(source: File, destination: File, password: String?): String? =
        rewrite(
            source = source,
            destination = destination,
            password = password,
            operation = ::nativeFixPdf,
            failure = "qpdf could not fix this PDF.",
        )

    private fun rewrite(
        source: File,
        destination: File,
        password: String?,
        operation: (String, String, String?) -> String?,
        failure: String,
    ): String? {
        if (!loaded) return "qpdf is not available in this build."
        if (!source.isFile || source.length() <= 0L) {
            return "The source PDF is missing or empty."
        }

        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.part")
        temp.delete()

        return try {
            val error = operation(
                source.absolutePath,
                temp.absolutePath,
                password,
            )
            if (error != null) {
                temp.delete()
                return error
            }
            if (!temp.isFile || temp.length() <= 0L) {
                temp.delete()
                return "qpdf produced an empty file."
            }

            if (destination.exists() && !destination.delete()) {
                temp.delete()
                return "Couldn't replace the previous copy."
            }
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            null
        } catch (t: Throwable) {
            temp.delete()
            t.message ?: failure
        }
    }

    private external fun nativeLinearize(
        inputPath: String,
        outputPath: String,
        password: String?,
    ): String?

    private external fun nativeFixPdf(
        inputPath: String,
        outputPath: String,
        password: String?,
    ): String?
}
