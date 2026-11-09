package com.karako.pdfr.data

/** A group of consecutive logical text lines shown together on one page. */
data class TextPage(val start: Int, val count: Int)

/**
 * Groups text lines into pages once their pixel heights are known.
 *
 * Keeping the grouping separate from Compose's [androidx.compose.ui.text.TextMeasurer]
 * makes the edge cases testable on the JVM: an empty document, an exact fit, a
 * line that is taller than the page, and a page height that is still zero while
 * the view is being measured.
 */
object TextPagination {

    fun paginate(lineHeights: List<Int>, pageHeightPx: Int): List<TextPage> {
        if (lineHeights.isEmpty()) return listOf(TextPage(0, 0))
        if (pageHeightPx <= 0) return listOf(TextPage(0, lineHeights.size))

        val pages = ArrayList<TextPage>()
        var start = 0
        var used = 0
        lineHeights.forEachIndexed { index, rawHeight ->
            // A measured empty line can be zero pixels tall; still count it as
            // a line so a page never ends up with an infinite number of rows.
            val height = rawHeight.coerceAtLeast(1)
            if (used > 0 && used + height > pageHeightPx) {
                pages.add(TextPage(start, index - start))
                start = index
                used = 0
            }
            used += height
        }
        pages.add(TextPage(start, lineHeights.size - start))
        return pages
    }
}
