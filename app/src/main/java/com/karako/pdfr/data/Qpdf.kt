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
     * Applies the selected qpdf transformations to [source] and writes the
     * result to [destination].
     *
     * qpdf runs the whole option set in one pass. Linearization, stream
     * compression and image optimization are ordered internally by qpdf, so
     * no intermediate files are needed.
     *
     * Must be called on [dispatcher]. Returns null on success, or a
     * human-readable error. The destination is removed on failure.
     */
    fun normalize(
        source: File,
        destination: File,
        password: String?,
        options: NormalizeOptions,
    ): String? =
        rewrite(
            source = source,
            destination = destination,
            password = password,
            operation = { input, output, secret ->
                nativeNormalize(
                    inputPath = input,
                    outputPath = output,
                    password = secret,
                    compressStreams = options.compressStreams,
                    decodeAllStreams = options.decodeAllStreams,
                    recompressFlate = options.recompressFlate,
                    compressionLevel9 = options.compressionLevel9,
                    jpegQuality = options.jpegQuality,
                    optimizeImages = options.optimizeImages,
                    newlineBeforeEndstream = options.newlineBeforeEndstream,
                    removeUnreferencedResources = options.removeUnreferencedResources,
                    externalizeInlineImages = options.externalizeInlineImages,
                    linearize = options.linearize,
                    pdfA = options.pdfA,
                )
            },
            failure = "qpdf could not normalize this PDF.",
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

    private external fun nativeNormalize(
        inputPath: String,
        outputPath: String,
        password: String?,
        compressStreams: Boolean,
        decodeAllStreams: Boolean,
        recompressFlate: Boolean,
        compressionLevel9: Boolean,
        jpegQuality: Int,
        optimizeImages: Boolean,
        newlineBeforeEndstream: Boolean,
        removeUnreferencedResources: Boolean,
        externalizeInlineImages: Boolean,
        linearize: Boolean,
        pdfA: Boolean,
    ): String?

    private external fun nativeFixPdf(
        inputPath: String,
        outputPath: String,
        password: String?,
    ): String?
}
