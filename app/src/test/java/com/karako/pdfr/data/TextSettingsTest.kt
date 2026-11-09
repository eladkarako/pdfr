package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSettingsTest {

    @Test
    fun defaultsToTwelvePoints() {
        assertEquals(12, TextSettings.DEFAULT_SIZE_PT)
        assertEquals(12, TextSettings.sizePtOrDefault(null))
    }

    @Test
    fun clampsBelowAndAboveTheMenuRange() {
        assertEquals(5, TextSettings.MIN_SIZE_PT)
        assertEquals(40, TextSettings.MAX_SIZE_PT)
        assertEquals(5, TextSettings.clampSizePt(0))
        assertEquals(5, TextSettings.clampSizePt(-9))
        assertEquals(40, TextSettings.clampSizePt(41))
        assertEquals(40, TextSettings.clampSizePt(999))
    }

    @Test
    fun keepsEveryValueInsideTheRange() {
        for (points in TextSettings.MIN_SIZE_PT..TextSettings.MAX_SIZE_PT) {
            assertEquals(points, TextSettings.sizePtOrDefault(points))
        }
    }

    @Test
    fun restoresAStoredSizeButRepairsAnInvalidOne() {
        assertEquals(7, TextSettings.sizePtOrDefault(7))
        assertEquals(5, TextSettings.sizePtOrDefault(1))
        assertEquals(40, TextSettings.sizePtOrDefault(64))
        assertEquals(12, TextSettings.sizePtOrDefault(null))
    }
}
