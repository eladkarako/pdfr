package com.karako.pdfr.data

import android.content.res.Resources
import com.karako.pdfr.R

/**
 * Reader defaults loaded from `res/values/bools.xml`.
 *
 * These used to be `ReaderConfig` constants. Moving them into resources keeps
 * the true/false switches in one declarative place, so a build or a translation
 * overlay can change a default without a code edit. The view model reads them
 * once and passes the values explicitly when it builds
 * [com.karako.pdfr.ui.ReaderState.Ready].
 */
class ReaderDefaults(resources: Resources) {

    val singlePage: Boolean = resources.getBoolean(R.bool.reader_single_page_default)
    val lineWrap: Boolean = resources.getBoolean(R.bool.reader_line_wrap_default)
    val alignRight: Boolean = resources.getBoolean(R.bool.reader_align_right_default)
    val lineNumbers: Boolean = resources.getBoolean(R.bool.reader_line_numbers_default)
    val monospace: Boolean = resources.getBoolean(R.bool.reader_monospace_default)
}
