package com.karako.pdfr.data

/**
 * The one place that defines the reflowable text-size range and its default.
 * The reader, the saved state and the text-size dialog all go through this so
 * a value restored from disk cannot escape the menu's 5-40 pt promise.
 */
object TextSettings {

    const val MIN_SIZE_PT = 5
    const val MAX_SIZE_PT = 40
    const val DEFAULT_SIZE_PT = 12

    fun clampSizePt(value: Int): Int = value.coerceIn(MIN_SIZE_PT, MAX_SIZE_PT)

    /** Restores a saved size, clamping out-of-range values and filling a missing one. */
    fun sizePtOrDefault(value: Int?): Int =
        value?.let(::clampSizePt) ?: DEFAULT_SIZE_PT
}
