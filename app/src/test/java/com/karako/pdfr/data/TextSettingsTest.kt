package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSettingsTest {

    private val settings = TextSettings(
        minSizePt = 5,
        maxSizePt = 40,
        defaultSizePt = 12,
    )

    @Test
    fun defaultsToTwelvePoints() {
        assertEquals(12, settings.defaultSizePt)
        assertEquals(12, settings.sizePtOrDefault(null))
    }

    @Test
    fun clampsBelowAndAboveTheMenuRange() {
        assertEquals(5, settings.minSizePt)
        assertEquals(40, settings.maxSizePt)
        assertEquals(5, settings.clampSizePt(0))
        assertEquals(5, settings.clampSizePt(-9))
        assertEquals(40, settings.clampSizePt(41))
        assertEquals(40, settings.clampSizePt(999))
    }

    @Test
    fun keepsEveryValueInsideTheRange() {
        for (points in settings.minSizePt..settings.maxSizePt) {
            assertEquals(points, settings.sizePtOrDefault(points))
        }
    }

    @Test
    fun restoresAStoredSizeButRepairsAnInvalidOne() {
        assertEquals(7, settings.sizePtOrDefault(7))
        assertEquals(5, settings.sizePtOrDefault(1))
        assertEquals(40, settings.sizePtOrDefault(64))
        assertEquals(12, settings.sizePtOrDefault(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsADefaultOutsideTheRange() {
        TextSettings(minSizePt = 5, maxSizePt = 40, defaultSizePt = 99)
    }
}
