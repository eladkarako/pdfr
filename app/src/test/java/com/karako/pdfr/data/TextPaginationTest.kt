package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPaginationTest {

    @Test
    fun anEmptyDocumentStillHasOneEmptyPage() {
        assertEquals(listOf(TextPage(0, 0)), TextPagination.paginate(emptyList(), 100))
    }

    @Test
    fun anUnmeasuredViewportFallsBackToASinglePage() {
        assertEquals(
            listOf(TextPage(0, 4)),
            TextPagination.paginate(listOf(10, 10, 10, 10), 0),
        )
    }

    @Test
    fun groupsLinesThatFitExactly() {
        assertEquals(
            listOf(TextPage(0, 3), TextPage(3, 2)),
            TextPagination.paginate(listOf(10, 10, 10, 10, 10), 30),
        )
    }

    @Test
    fun startsANewPageInsteadOfSplittingALine() {
        assertEquals(
            listOf(TextPage(0, 2), TextPage(2, 2), TextPage(4, 1)),
            TextPagination.paginate(listOf(10, 10, 15, 10, 5), 25),
        )
    }

    @Test
    fun aLineTallerThanThePageGetsItsOwnPage() {
        assertEquals(
            listOf(TextPage(0, 1), TextPage(1, 2)),
            TextPagination.paginate(listOf(120, 10, 10), 100),
        )
    }

    @Test
    fun zeroHeightLinesStillAdvanceThroughTheDocument() {
        val pages = TextPagination.paginate(listOf(0, 0, 0), 2)
        assertEquals(listOf(TextPage(0, 2), TextPage(2, 1)), pages)
    }

    @Test
    fun pagesCoverEveryLineExactlyOnce() {
        val heights = listOf(7, 19, 3, 41, 8, 8, 2, 30, 5)
        val pages = TextPagination.paginate(heights, 40)

        assertEquals(0, pages.first().start)
        var next = 0
        for (page in pages) {
            assertEquals(next, page.start)
            assertTrue(page.count > 0)
            next += page.count
        }
        assertEquals(heights.size, next)
    }
}
