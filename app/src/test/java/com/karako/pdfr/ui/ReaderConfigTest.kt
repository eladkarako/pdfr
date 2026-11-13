package com.karako.pdfr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the reader defaults and saved-state keys that were moved into
 * [ReaderConfig]. The keys are part of the on-device format: renaming one
 * silently loses the restored document, so they are asserted literally.
 */
class ReaderConfigTest {

    @Test
    fun textDefaultsMatchTheDocumentedReaderBehaviour() {
        assertTrue(ReaderConfig.DEFAULT_SINGLE_PAGE)
        assertTrue(ReaderConfig.DEFAULT_LINE_WRAP)
        assertTrue(ReaderConfig.DEFAULT_LINE_NUMBERS)
        assertTrue(ReaderConfig.DEFAULT_MONOSPACE)
        assertEquals(false, ReaderConfig.DEFAULT_ALIGN_RIGHT)
    }

    @Test
    fun viewportAndPointConstantsArePositive() {
        assertTrue(ReaderConfig.DEFAULT_PAGE_ASPECT > 1f)
        assertTrue(ReaderConfig.MIN_VIEWPORT_WIDTH_PX > 0)
        assertTrue(ReaderConfig.MIN_VIEWPORT_HEIGHT_PX > 0)
        assertEquals(72f, ReaderConfig.POINTS_PER_INCH, 0f)
        assertEquals(160f, ReaderConfig.SCALED_PIXELS_PER_INCH, 0f)
        assertTrue(ReaderConfig.TEXT_LINE_HEIGHT > 1f)
    }

    @Test
    fun timingsAndGestureThresholdsArePositive() {
        assertTrue(ReaderConfig.RENDER_SETTLE_MS > 0)
        assertTrue(ReaderConfig.CONTENT_WIDTH_SETTLE_MS > 0)
        assertTrue(ReaderConfig.ZOOM_ANIMATION_MS > 0)
        assertTrue(ReaderConfig.POST_PINCH_SETTLE_MS > 0)
        assertTrue(ReaderConfig.POST_PINCH_RENDER_MS > 0)
        assertTrue(ReaderConfig.PINCH_START_DISTANCE > 0f)
        assertTrue(ReaderConfig.REFLOW_DEBOUNCE_MS > 0)
        assertTrue(ReaderConfig.BITMAP_EVICT_DEBOUNCE_MS > 0)
    }

    @Test
    fun savedStateKeysAreUniqueAndKeepTheirWireNames() {
        val keys = listOf(
            ReaderConfig.KEY_PATH,
            ReaderConfig.KEY_NAME,
            ReaderConfig.KEY_MIME,
            ReaderConfig.KEY_PAGE,
            ReaderConfig.KEY_MONO,
            ReaderConfig.KEY_MTIME,
            ReaderConfig.KEY_TEXT_SIZE,
            ReaderConfig.KEY_SINGLE_PAGE,
            ReaderConfig.KEY_LINE_WRAP,
            ReaderConfig.KEY_ALIGN_RIGHT,
            ReaderConfig.KEY_LINE_NUMBERS,
        )

        assertEquals(keys.size, keys.toSet().size)
        assertEquals("document_path", ReaderConfig.KEY_PATH)
        assertEquals("document_name", ReaderConfig.KEY_NAME)
        assertEquals("document_mime", ReaderConfig.KEY_MIME)
        assertEquals("document_page", ReaderConfig.KEY_PAGE)
        assertEquals("document_monospace", ReaderConfig.KEY_MONO)
        assertEquals("document_modified", ReaderConfig.KEY_MTIME)
        assertEquals("reader_text_size_pt", ReaderConfig.KEY_TEXT_SIZE)
        assertEquals("reader_single_page", ReaderConfig.KEY_SINGLE_PAGE)
        assertEquals("reader_line_wrap", ReaderConfig.KEY_LINE_WRAP)
        assertEquals("reader_align_right", ReaderConfig.KEY_ALIGN_RIGHT)
        assertEquals("reader_line_numbers", ReaderConfig.KEY_LINE_NUMBERS)
    }
}
