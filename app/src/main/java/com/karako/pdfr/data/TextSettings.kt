package com.karako.pdfr.data

import android.content.res.Resources
import com.karako.pdfr.R

/**
 * The reflowable text-size range and its default.
 *
 * The numbers live in `res/values/integers.xml`, so the text-size dialog, the
 * view model's clamp and the saved-state repair all read the same range. The
 * class itself is plain Kotlin, which keeps the clamping logic testable on the
 * JVM without an Android runtime.
 */
class TextSettings(
    val minSizePt: Int,
    val maxSizePt: Int,
    val defaultSizePt: Int,
) {

    init {
        require(minSizePt <= defaultSizePt && defaultSizePt <= maxSizePt) {
            "TextSettings default must be inside its range"
        }
    }

    fun clampSizePt(value: Int): Int = value.coerceIn(minSizePt, maxSizePt)

    /** Restores a saved size, clamping out-of-range values and filling a missing one. */
    fun sizePtOrDefault(value: Int?): Int =
        value?.let(::clampSizePt) ?: defaultSizePt

    companion object {
        fun fromResources(resources: Resources): TextSettings = TextSettings(
            minSizePt = resources.getInteger(R.integer.text_size_min_pt),
            maxSizePt = resources.getInteger(R.integer.text_size_max_pt),
            defaultSizePt = resources.getInteger(R.integer.text_size_default_pt),
        )
    }
}
