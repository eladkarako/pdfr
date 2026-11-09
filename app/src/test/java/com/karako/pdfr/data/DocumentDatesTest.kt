package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DocumentDatesTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
    ): Long = Calendar.getInstance(utc).apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis

    @Test
    fun formatsAndParsesTheEditorShape() {
        val value = millis(2026, 11, 8, 14, 30, 5)
        assertEquals("2026-11-08T14:30:05", DocumentDates.formatIso(value, utc))
        assertEquals(value, DocumentDates.parseIso("2026-11-08T14:30:05", utc))
    }

    @Test
    fun rejectsTrailingGarbageAndImpossibleDates() {
        assertNull(DocumentDates.parseIso("2026-11-08T14:30:05junk", utc))
        assertNull(DocumentDates.parseIso("2026-13-08T14:30:05", utc))
        assertNull(DocumentDates.parseIso("", utc))
        assertNull(DocumentDates.parseIso(null, utc))
    }

    @Test
    fun normalisesPdfDatesWithoutAnOffset() {
        assertEquals(
            "2026-01-02T03:04:05",
            DocumentDates.normalise("D:20260102030405", utc),
        )
        assertEquals(
            "2026-01-02T03:04:05",
            DocumentDates.normalise("D:20260102030405Z", utc),
        )
    }

    @Test
    fun normalisesPdfDatesWithAnOffset() {
        assertEquals(
            "2026-01-02T10:00:00",
            DocumentDates.normalise("D:20260102120000+02'00'", utc),
        )
        assertEquals(
            "2026-01-02T10:00:00",
            DocumentDates.normalise("D:20260102120000+0200", utc),
        )
    }

    @Test
    fun acceptsPdfDatesWithOnlyAYear() {
        assertEquals("2026-01-01T00:00:00", DocumentDates.normalise("D:2026", utc))
    }

    @Test
    fun keepsUnknownDatesEditableInsteadOfDroppingThem() {
        assertEquals("not a date", DocumentDates.normalise("  not a date  ", utc))
    }

    @Test
    fun convertsIsoBackToPdfSyntax() {
        assertEquals(
            "D:20260102030405",
            DocumentDates.toPdfDate("2026-01-02T03:04:05", utc),
        )
        assertNull(DocumentDates.toPdfDate(null, utc))
        assertEquals("", DocumentDates.toPdfDate("", utc))
    }
}
