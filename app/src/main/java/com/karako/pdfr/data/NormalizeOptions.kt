package com.karako.pdfr.data

import android.content.res.Resources
import com.karako.pdfr.R

/**
 * The qpdf transformations exposed by the Normalize action.
 *
 * qpdf applies all of these in a single pass, in its own internal order, so a
 * Normalize run never has to write and reopen intermediate PDFs. The flags
 * mirror the qpdf command-line options one-for-one:
 *
 * - [compressStreams] -> `--compress-streams=y|n`
 * - [decodeAllStreams] -> `--decode-level=all`
 * - [recompressFlate] -> `--recompress-flate`
 * - [compressionLevel9] -> `--compression-level=9`
 * - [optimizeImages] -> `--optimize-images`
 * - [jpegQuality] -> `--jpeg-quality=N` (only used with [optimizeImages])
 * - [newlineBeforeEndstream] -> `--newline-before-endstream`
 * - [removeUnreferencedResources] -> `--remove-unreferenced-resources=yes`
 * - [externalizeInlineImages] -> `--externalize-inline-images`
 * - [linearize] -> `--linearize`
 * - [pdfA] -> PDF/A-2b identification and an sRGB output intent
 *
 * PDF/A is not a qpdf writer option. When [pdfA] is set, qpdf decrypts the
 * output, adds the PDF/A XMP identification and an sRGB output intent, and
 * requires PDF 1.7 and a newline before each `endstream`. Fonts and content
 * are not converted, so the result is a best-effort archival copy.
 */
data class NormalizeOptions(
    val compressStreams: Boolean = true,
    val decodeAllStreams: Boolean = false,
    val recompressFlate: Boolean = false,
    val compressionLevel9: Boolean = false,
    val optimizeImages: Boolean = false,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    val newlineBeforeEndstream: Boolean = true,
    val removeUnreferencedResources: Boolean = true,
    val externalizeInlineImages: Boolean = false,
    val linearize: Boolean = false,
    val pdfA: Boolean = false,
) {

    /** True when at least one option would change the output. */
    val hasWork: Boolean
        get() = compressStreams || decodeAllStreams || recompressFlate ||
                compressionLevel9 || optimizeImages || newlineBeforeEndstream ||
                removeUnreferencedResources || externalizeInlineImages || linearize || pdfA

    /** JPEG quality is only meaningful when qpdf is re-encoding images. */
    val jpegQualityValid: Boolean
        get() = jpegQuality in MIN_JPEG_QUALITY..MAX_JPEG_QUALITY

    val canApply: Boolean
        get() = hasWork && (!optimizeImages || jpegQualityValid)

    companion object {
        const val MIN_JPEG_QUALITY = 0
        const val MAX_JPEG_QUALITY = 100
        const val DEFAULT_JPEG_QUALITY = 70

        /**
         * The Normalize dialog's starting options, read from resources.
         *
         * The data-class defaults above stay as a safe fallback for JVM tests
         * and for callers that do not have a [Resources] instance. The dialog
         * uses this factory so the true/false switches are declared in
         * `res/values/bools.xml`.
         */
        fun fromResources(resources: Resources): NormalizeOptions = NormalizeOptions(
            compressStreams = resources.getBoolean(R.bool.normalize_compress_streams_default),
            decodeAllStreams = resources.getBoolean(R.bool.normalize_decode_all_streams_default),
            recompressFlate = resources.getBoolean(R.bool.normalize_recompress_flate_default),
            compressionLevel9 = resources.getBoolean(R.bool.normalize_compression_level_9_default),
            optimizeImages = resources.getBoolean(R.bool.normalize_optimize_images_default),
            jpegQuality = resources.getInteger(R.integer.normalize_jpeg_quality_default),
            newlineBeforeEndstream =
                resources.getBoolean(R.bool.normalize_newline_before_endstream_default),
            removeUnreferencedResources =
                resources.getBoolean(R.bool.normalize_remove_unreferenced_resources_default),
            externalizeInlineImages =
                resources.getBoolean(R.bool.normalize_externalize_inline_images_default),
            linearize = resources.getBoolean(R.bool.normalize_linearize_default),
            pdfA = resources.getBoolean(R.bool.normalize_pdfa_default),
        )
    }
}
