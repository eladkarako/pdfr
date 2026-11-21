package com.karako.pdfr.ui

/**
 * Central timing, layout and persisted-state values for the reader UI.
 *
 * These are deliberately not Android resources: most are floats, millisecond
 * durations or opaque saved-state keys, and they are shared by composables and
 * the view model rather than inflated from XML. Grouping them here keeps the
 * reader's feel tunable in one place instead of scattered across files.
 *
 * Naming: UPPER_SNAKE_CASE for compile-time values, matching the rest of the
 * project's Kotlin constants.
 */
internal object ReaderConfig {

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    /** Fallback page ratio (A4) used until MuPDF reports real page bounds. */
    const val DEFAULT_PAGE_ASPECT = 1.4142f

    /** PDF points per inch, used to convert screen pixels into layout points. */
    const val POINTS_PER_INCH = 72f

    /** Android's scaled pixels per inch at font scale 1, used for point sizes. */
    const val SCALED_PIXELS_PER_INCH = 160f

    /** Line height multiplier for the plain-text reader. */
    const val TEXT_LINE_HEIGHT = 1.4f

    /**
     * Viewport used before the first real measurement, in pixels. Kept large
     * enough that the initial layout is not degenerate on small screens.
     */
    const val MIN_VIEWPORT_WIDTH_PX = 200
    const val MIN_VIEWPORT_HEIGHT_PX = 300

    /** Menus and dialogs never cover more than this share of the screen. */
    const val DIALOG_MAX_HEIGHT_FRACTION = 0.75f

    // -----------------------------------------------------------------------
    // Render and gesture settle timings (milliseconds)
    // -----------------------------------------------------------------------

    /** Wait for the first bitmap before treating the page as settled. */
    const val RENDER_SETTLE_MS = 120L

    /** Wait for a reflow layout to report its final content width. */
    const val CONTENT_WIDTH_SETTLE_MS = 200L

    /** Duration of the animated double-tap zoom step. */
    const val ZOOM_ANIMATION_MS = 260

    /** Quiet period after the fingers lift before the final reflow starts. */
    const val POST_PINCH_SETTLE_MS = 700L

    /**
     * After the deferred reflow lands, the scroll rack keeps old pages wide
     * until [CONTENT_WIDTH_SETTLE_MS] has passed and the replacement bitmaps
     * have rendered. The pinch anchor must stay alive through that second
     * resize, or the content jumps once more after the gesture.
     */
    const val POST_PINCH_RENDER_MS = 420L

    /** Minimum finger travel before a pinch is recognised, in pixels. */
    const val PINCH_START_DISTANCE = 8f

    // -----------------------------------------------------------------------
    // Selection outline
    // -----------------------------------------------------------------------

    // The fill and outline colours themselves live in res/values/colors.xml
    // (selection_fill / selection_outline) so they can be themed like the rest
    // of the palette.

    // -----------------------------------------------------------------------
    // Text defaults
    // -----------------------------------------------------------------------

    // The boolean defaults for the reflowable text options now live in
    // res/values/bools.xml and are read through ReaderDefaults. They were
    // moved so the true/false switches are declarative rather than buried in
    // Kotlin.

    // -----------------------------------------------------------------------
    // Saved-state keys
    // -----------------------------------------------------------------------

    // Stable across process death and app upgrades; renaming one loses the
    // restored document or setting it describes.
    const val KEY_PATH = "document_path"
    const val KEY_NAME = "document_name"
    const val KEY_MIME = "document_mime"
    const val KEY_PAGE = "document_page"
    const val KEY_MONO = "document_monospace"
    const val KEY_MTIME = "document_modified"
    const val KEY_TEXT_SIZE = "reader_text_size_pt"
    const val KEY_SINGLE_PAGE = "reader_single_page"
    const val KEY_LINE_WRAP = "reader_line_wrap"
    const val KEY_ALIGN_RIGHT = "reader_align_right"
    const val KEY_LINE_NUMBERS = "reader_line_numbers"

    // -----------------------------------------------------------------------
    // Debounce timings (milliseconds)
    // -----------------------------------------------------------------------

    /** Coalesces rapid text-size or zoom changes into one reflow. */
    const val REFLOW_DEBOUNCE_MS = 80L

    /** Delay before evicting bitmaps the new layout no longer uses. */
    const val BITMAP_EVICT_DEBOUNCE_MS = 240L
}
