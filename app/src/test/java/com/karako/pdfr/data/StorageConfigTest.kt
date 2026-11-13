package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the cache naming rules extracted into [StorageConfig] and used by
 * [DocumentStore]. The UTF-8 byte limit matters for CJK names, and the
 * sidecar suffix is part of the on-device cache format.
 */
class StorageConfigTest {

    @Test
    fun nameLimitsLeaveRoomForAnExtension() {
        assertTrue(StorageConfig.MAX_NAME_BYTES in 64..255)
        assertTrue(StorageConfig.MIN_STEM_BYTES > 0)
        assertTrue(StorageConfig.MIN_STEM_BYTES < StorageConfig.MAX_NAME_BYTES)
        assertTrue(StorageConfig.MAX_PATH_SEGMENT_LENGTH >= StorageConfig.MAX_NAME_BYTES)
    }

    @Test
    fun sidecarAndShareNamesAreHiddenAndDistinct() {
        assertTrue(StorageConfig.SIDECAR_SUFFIX.startsWith("."))
        assertTrue(StorageConfig.SIDECAR_SUFFIX.contains("meta"))
        assertTrue(StorageConfig.SHARE_DIR_PREFIX.endsWith("-"))
        assertFalse(StorageConfig.IMPORT_STAGING_NAME.startsWith("."))
        assertTrue(StorageConfig.IMPORT_STAGING_NAME.endsWith(".tmp"))
    }

    @Test
    fun timestampThresholdDistinguishesSecondsFromMilliseconds() {
        // 1.7e9 is a plausible epoch-seconds value; 1.7e12 is milliseconds.
        assertTrue(1_700_000_000L < StorageConfig.EPOCH_MILLIS_THRESHOLD)
        assertTrue(1_700_000_000_000L > StorageConfig.EPOCH_MILLIS_THRESHOLD)
        assertEquals(1000L, StorageConfig.MILLIS_PER_SECOND)
    }

    @Test
    fun safeFileNameReplacesIllegalCharactersAndKeepsUnicode() {
        assertEquals("a_b_c_.pdf", DocumentStore.safeFileName("a:b*c?.pdf"))
        assertEquals("עברית 中文 😀.txt", DocumentStore.safeFileName("עברית 中文 😀.txt"))
    }

    @Test
    fun safeFileNameFallsBackWhenNothingUsableRemains() {
        assertEquals(StorageConfig.FALLBACK_NAME, DocumentStore.safeFileName(""))
        assertEquals(StorageConfig.FALLBACK_NAME, DocumentStore.safeFileName("..."))
        assertEquals(StorageConfig.FALLBACK_NAME, DocumentStore.safeFileName("   "))
    }

    @Test
    fun safeFileNameCapsTheStemByUtf8BytesNotCharacters() {
        val name = DocumentStore.safeFileName("中".repeat(200) + ".txt")

        assertTrue(name.endsWith(".txt"))
        assertTrue(
            "the whole name must fit the configured byte budget",
            name.toByteArray(Charsets.UTF_8).size <= StorageConfig.MAX_NAME_BYTES,
        )
        assertTrue(name.length > 1)
    }

    @Test
    fun safeFileNameKeepsAShortExtension() {
        assertEquals("report.pdf", DocumentStore.safeFileName("report.pdf"))
        assertEquals("report.tar.gz", DocumentStore.safeFileName("report.tar.gz"))
        assertEquals(
            "opaque.longextension",
            DocumentStore.safeFileName("opaque.longextension"),
        )
    }
}
