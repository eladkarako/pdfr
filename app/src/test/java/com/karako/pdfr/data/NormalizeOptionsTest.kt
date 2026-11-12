package com.karako.pdfr.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizeOptionsTest {

    @Test
    fun defaultsAreUsefulAndSafe() {
        val options = NormalizeOptions()

        assertTrue(options.compressStreams)
        assertTrue(options.newlineBeforeEndstream)
        assertTrue(options.removeUnreferencedResources)
        assertFalse(options.decodeAllStreams)
        assertFalse(options.recompressFlate)
        assertFalse(options.compressionLevel9)
        assertFalse(options.optimizeImages)
        assertFalse(options.externalizeInlineImages)
        assertFalse(options.linearize)
        assertFalse(options.pdfA)
        assertTrue(options.canApply)
    }

    @Test
    fun turningEveryOptionOffDisablesApply() {
        val options = NormalizeOptions(
            compressStreams = false,
            decodeAllStreams = false,
            recompressFlate = false,
            compressionLevel9 = false,
            optimizeImages = false,
            newlineBeforeEndstream = false,
            removeUnreferencedResources = false,
            externalizeInlineImages = false,
            linearize = false,
            pdfA = false,
        )

        assertFalse(options.hasWork)
        assertFalse(options.canApply)
    }

    @Test
    fun jpegQualityMustBeValidWhenImagesAreOptimized() {
        assertTrue(NormalizeOptions(optimizeImages = true, jpegQuality = 0).canApply)
        assertTrue(NormalizeOptions(optimizeImages = true, jpegQuality = 100).canApply)
        assertFalse(NormalizeOptions(optimizeImages = true, jpegQuality = -1).canApply)
        assertFalse(NormalizeOptions(optimizeImages = true, jpegQuality = 101).canApply)
    }

    @Test
    fun invalidJpegQualityIsIgnoredWhenImagesAreNotOptimized() {
        val options = NormalizeOptions(
            optimizeImages = false,
            jpegQuality = 101,
        )

        assertFalse(options.jpegQualityValid)
        assertTrue(options.canApply)
    }

    @Test
    fun anImageOnlyRunNeedsImageOptimization() {
        val options = NormalizeOptions(
            compressStreams = false,
            newlineBeforeEndstream = false,
            removeUnreferencedResources = false,
            optimizeImages = true,
            jpegQuality = 70,
        )

        assertTrue(options.hasWork)
        assertTrue(options.canApply)
    }
}
