package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSizeTest {

    private val tolerance = 0.01f

    @Test
    fun a4IsTheExpectedNumberOfPoints() {
        // A4 is 210 x 297 mm, and one inch is 25.4 mm / 72 pt.
        assertEquals(595.28f, IsoPaperSize.A4.widthPt, tolerance)
        assertEquals(841.89f, IsoPaperSize.A4.heightPt, tolerance)
    }

    @Test
    fun everyUnitConvertsToTheSamePhysicalSize() {
        // 25.4 mm, 2.54 cm, 1 inch, 72 pt, 96 px and 6 em all describe the
        // same length in points.
        assertEquals(72f, PageSizeUnit.MILLIMETERS.toPoints(25.4f), tolerance)
        assertEquals(72f, PageSizeUnit.CENTIMETERS.toPoints(2.54f), tolerance)
        assertEquals(72f, PageSizeUnit.INCHES.toPoints(1f), tolerance)
        assertEquals(72f, PageSizeUnit.POINTS.toPoints(72f), tolerance)
        assertEquals(72f, PageSizeUnit.PIXELS.toPoints(96f), tolerance)
        assertEquals(72f, PageSizeUnit.EM.toPoints(6f), tolerance)
    }

    @Test
    fun conversionsRoundTrip() {
        val points = 210f * PageSizeUnit.MILLIMETERS.pointsPerUnit
        for (unit in PageSizeUnit.entries) {
            val value = unit.fromPoints(points)
            val roundTripped = unit.toPoints(value)
            assertEquals(points, roundTripped, tolerance)
        }
    }

    @Test
    fun commaIsAcceptedAsDecimalSeparator() {
        assertEquals(2.5f, PageSize.numberOrNull("2,5")!!, tolerance)
        assertEquals(2.5f, PageSize.numberOrNull(" 2.5 ")!!, tolerance)
    }

    @Test
    fun numbersMustBePositiveAndFinite() {
        assertNull(PageSize.numberOrNull(""))
        assertNull(PageSize.numberOrNull("   "))
        assertNull(PageSize.numberOrNull("0"))
        assertNull(PageSize.numberOrNull("-5"))
        assertNull(PageSize.numberOrNull("abc"))
        assertNull(PageSize.numberOrNull("Infinity"))
        assertNull(PageSize.numberOrNull("NaN"))
    }

    @Test
    fun pointsMustStayInsideThePdfBoxRange() {
        assertEquals(1f, PageSize.pointsOrNull("1", PageSizeUnit.POINTS)!!, tolerance)
        assertEquals(
            PageSize.MAX_POINTS,
            PageSize.pointsOrNull("14400", PageSizeUnit.POINTS)!!,
            tolerance,
        )
        assertNull(PageSize.pointsOrNull("0.5", PageSizeUnit.POINTS))
        assertNull(PageSize.pointsOrNull("14401", PageSizeUnit.POINTS))
        // 5080 mm is about 14400 pt; 5081 mm is just over the limit.
        assertEquals(
            14400f,
            PageSize.pointsOrNull("5080", PageSizeUnit.MILLIMETERS)!!,
            1f,
        )
        assertNull(PageSize.pointsOrNull("5081", PageSizeUnit.MILLIMETERS))
    }

    @Test
    fun formattingRoundsAndDropsTrailingZeros() {
        assertEquals("595.28", PageSize.format(595.2756f))
        assertEquals("841.89", PageSize.format(841.8898f))
        assertEquals("210", PageSize.format(210f))
        assertEquals("12.35", PageSize.format(12.3456f))
    }

    @Test
    fun describeUsesPoints() {
        assertEquals("595.28 x 841.89 pt", PageSize.describe(595.2756f, 841.8898f))
    }

    @Test
    fun isoSeriesGrowFromA10ToA0() {
        // Each A size is the next one folded in half, so A0 must be the
        // largest and A10 the smallest of the A series.
        assertEquals(841f, IsoPaperSize.A0.widthMm, tolerance)
        assertEquals(1189f, IsoPaperSize.A0.heightMm, tolerance)
        assertEquals(26f, IsoPaperSize.A10.widthMm, tolerance)
        assertEquals(37f, IsoPaperSize.A10.heightMm, tolerance)

        val aWidths = IsoPaperSize.entries
            .filter { it.name.startsWith("A") }
            .map { it.widthMm }
        assertEquals(IsoPaperSize.A0.widthMm, aWidths.maxOrNull()!!, tolerance)
        assertEquals(IsoPaperSize.A10.widthMm, aWidths.minOrNull()!!, tolerance)
    }

    @Test
    fun allIsoSizesArePortrait() {
        for (paper in IsoPaperSize.entries) {
            assertTrue(
                "${paper.name} should be portrait",
                paper.heightMm > paper.widthMm,
            )
        }
    }
}
