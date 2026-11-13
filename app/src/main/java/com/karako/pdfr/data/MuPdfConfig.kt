package com.karako.pdfr.data

/**
 * Layout, rendering and rewrite values for the MuPDF engine.
 *
 * MuPDF has no Android resources of its own, and these numbers describe how
 * PDFr asks it to lay out and rasterise a page rather than what the UI looks
 * like. They live here so the cache budget, the reflow limits and the rewrite
 * option string can be tuned without hunting through [MuPdfDocument].
 */
object MuPdfConfig {

    // -----------------------------------------------------------------------
    // Caches
    // -----------------------------------------------------------------------

    /** Structured-text pages kept for selection, independent of bitmaps. */
    const val TEXT_CACHE_PAGES = 3

    /** Share of the app heap the bitmap cache may use. */
    const val CACHE_HEAP_DIVISOR = 8L

    /** Lower and upper bounds for the bitmap cache, in bytes. */
    const val CACHE_MIN_BYTES = 24L * 1024 * 1024
    const val CACHE_MAX_BYTES = 64L * 1024 * 1024

    // -----------------------------------------------------------------------
    // Reflow layout
    // -----------------------------------------------------------------------

    /** Smallest page dimension passed to MuPDF's layout, in points. */
    const val MIN_LAYOUT_PT = 32f

    /** Reflow font-size range, in em units. */
    const val MIN_EM = 2f
    const val MAX_EM = 96f

    // -----------------------------------------------------------------------
    // Rasterisation
    // -----------------------------------------------------------------------

    /** Width range requested from MuPDF, in pixels. */
    const val MIN_RENDER_WIDTH = 64
    const val MAX_RENDER_WIDTH = 8_192

    /** Hard cap on a rendered page, independent of the width range. */
    const val MAX_RENDER_PIXELS = 6_000_000
    const val MAX_BITMAP_DIMENSION = 16_384

    // -----------------------------------------------------------------------
    // Stylesheets
    // -----------------------------------------------------------------------

    /** Overrides MuPDF's default `pre` font for plain-text documents. */
    const val MONOSPACE_CSS = "pre { font-family: monospace; }"
    const val PROPORTIONAL_CSS = "pre { font-family: sans-serif; }"

    /**
     * MuPDF's default HTML stylesheet leaves a wide body margin. Reflowable
     * documents should use the whole reader width, so remove page and body
     * margins before laying them out.
     */
    const val READER_CSS =
        "@page { margin: 0; } html, body { margin: 0; padding: 0; }"

    // -----------------------------------------------------------------------
    // Rewrite
    // -----------------------------------------------------------------------

    /**
     * Rewrites the PDF without encryption, with unused objects removed and
     * content streams, fonts and images compressed. MuPDF 1.28 cannot
     * linearize, so reorder, rotate and metadata writes use this set while the
     * qpdf-backed Normalize action handles linearization and fine tuning.
     */
    const val PDF_REWRITE_OPTIONS =
        "garbage=compact,compress=yes,compress-images=yes,compress-fonts=yes,objstms=yes,encrypt=none"
}
