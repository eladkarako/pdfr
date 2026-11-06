package com.karako.pdfr.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageRangesTest {

    private fun pages(input: String, pageCount: Int = 14): List<Int> {
        val result = PageRanges.parse(input, pageCount)
        check(result is PageRanges.Result.Ok) { "expected success but was $result" }
        return result.pages.toList()
    }

    private fun error(input: String, pageCount: Int = 14): PageRanges.Result.Error {
        val result = PageRanges.parse(input, pageCount)
        check(result is PageRanges.Result.Error) { "expected an error but was $result" }
        return result
    }

    @Test
    fun parsesCommaSeparatedPages() {
        assertArrayEquals(intArrayOf(1, 3, 7), pages("1,3,7").toIntArray())
    }

    @Test
    fun parsesInclusiveRanges() {
        assertArrayEquals(intArrayOf(2, 3, 4), pages("2-4").toIntArray())
    }

    @Test
    fun singlePageRangeIsAccepted() {
        assertArrayEquals(intArrayOf(3), pages("3-3").toIntArray())
    }

    @Test
    fun eMeansLastPage() {
        assertArrayEquals(intArrayOf(13, 14), pages("13-e").toIntArray())
        assertArrayEquals(intArrayOf(14), pages("E").toIntArray())
    }

    @Test
    fun ignoresWhitespaceEverywhere() {
        assertArrayEquals(
            intArrayOf(1, 2, 3, 7, 9, 10, 11, 12, 13, 14, 3),
            pages(" 1 - 3 , 7 , 9 - e , 3 ").toIntArray(),
        )
    }

    @Test
    fun keepsOrderAndDuplicates() {
        assertArrayEquals(intArrayOf(3, 1, 3), pages("3,1,3").toIntArray())
    }

    @Test
    fun worksForSinglePageDocuments() {
        assertArrayEquals(intArrayOf(1), pages("1", pageCount = 1).toIntArray())
        assertArrayEquals(intArrayOf(1), pages("e", pageCount = 1).toIntArray())
    }

    @Test
    fun rejectsBlankInput() {
        assertEquals(PageRanges.Reason.EMPTY, error("   ").reason)
    }

    @Test
    fun rejectsEmptyEntry() {
        assertEquals(PageRanges.Reason.EMPTY_ENTRY, error("1,,2").reason)
        assertEquals(PageRanges.Reason.EMPTY_ENTRY, error("1,").reason)
    }

    @Test
    fun rejectsBadTokens() {
        val bad = error("1,a")
        assertEquals(PageRanges.Reason.BAD_TOKEN, bad.reason)
        assertEquals("a", bad.token)

        assertEquals(PageRanges.Reason.BAD_TOKEN, error("1-").reason)
        assertEquals(PageRanges.Reason.BAD_TOKEN, error("-3").reason)
        assertEquals(PageRanges.Reason.BAD_TOKEN, error("1.5").reason)
    }

    @Test
    fun rejectsOutOfRangePages() {
        val tooBig = error("15")
        assertEquals(PageRanges.Reason.OUT_OF_RANGE, tooBig.reason)
        assertEquals(15, tooBig.page)

        assertEquals(0, error("0").page)
        assertEquals(PageRanges.Reason.OUT_OF_RANGE, error("1-15").reason)
    }

    @Test
    fun rejectsReversedRanges() {
        val reversed = error("5-2")
        assertEquals(PageRanges.Reason.REVERSED, reversed.reason)
        assertEquals("5-2", reversed.token)
    }

    @Test
    fun rejectsDocumentsWithoutPages() {
        assertEquals(PageRanges.Reason.OUT_OF_RANGE, error("1", pageCount = 0).reason)
    }

    @Test
    fun enforcesPageLimit() {
        // 60,000 pages is above the 50,000-page safety cap.
        assertEquals(PageRanges.Reason.TOO_MANY, error("1-e", pageCount = 60_000).reason)
    }

    @Test
    fun errorTokenIsNullForUnknownNumbers() {
        assertNull(error("nope").page)
    }
}
