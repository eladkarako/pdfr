package com.karako.pdfr.data

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
    }
}
