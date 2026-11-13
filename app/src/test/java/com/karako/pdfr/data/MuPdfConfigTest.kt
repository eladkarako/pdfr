package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the MuPDF tuning values that used to be scattered through
 * [MuPdfDocument]. These tests are about the shape of the configuration, not
 * about MuPDF itself.
 */
class MuPdfConfigTest {

    @Test
    fun cacheBudgetIsBoundedAndUsesPartOfTheHeap() {
        assertTrue(MuPdfConfig.CACHE_HEAP_DIVISOR >= 2)
        assertTrue(MuPdfConfig.CACHE_MIN_BYTES > 0)
        assertTrue(MuPdfConfig.CACHE_MIN_BYTES < MuPdfConfig.CACHE_MAX_BYTES)
        assertTrue(MuPdfConfig.TEXT_CACHE_PAGES > 0)
    }

    @Test
    fun layoutAndRenderLimitsAreOrdered() {
        assertTrue(MuPdfConfig.MIN_LAYOUT_PT > 0f)
        assertTrue(MuPdfConfig.MIN_EM < MuPdfConfig.MAX_EM)
        assertTrue(MuPdfConfig.MIN_RENDER_WIDTH < MuPdfConfig.MAX_RENDER_WIDTH)
        assertTrue(MuPdfConfig.MAX_RENDER_PIXELS > 0)
        assertTrue(MuPdfConfig.MAX_BITMAP_DIMENSION >= MuPdfConfig.MAX_RENDER_WIDTH)
    }

    @Test
    fun readerStylesheetRemovesDefaultPageMargins() {
        assertTrue(MuPdfConfig.READER_CSS.contains("margin: 0"))
        assertTrue(MuPdfConfig.READER_CSS.contains("padding: 0"))
        assertTrue(MuPdfConfig.MONOSPACE_CSS.contains("monospace"))
        assertTrue(MuPdfConfig.PROPORTIONAL_CSS.contains("sans-serif"))
    }

    @Test
    fun rewriteOptionsStayUnencryptedAndCompressed() {
        val options = MuPdfConfig.PDF_REWRITE_OPTIONS

        assertTrue(options.contains("garbage=compact"))
        assertTrue(options.contains("compress=yes"))
        assertTrue(options.contains("compress-images=yes"))
        assertTrue(options.contains("compress-fonts=yes"))
        assertTrue(options.contains("objstms=yes"))
        assertTrue(options.contains("encrypt=none"))
        assertEquals(
            "the public alias must stay identical to the central value",
            MuPdfConfig.PDF_REWRITE_OPTIONS,
            MuPdfDocument.PDF_REWRITE_OPTIONS,
        )
    }
}
