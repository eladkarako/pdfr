package com.karako.pdfr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karako.pdfr.data.ImageMetadata
import com.karako.pdfr.data.MetadataField
import com.karako.pdfr.data.MetadataKeys
import com.karako.pdfr.data.MuPdf
import com.karako.pdfr.data.MuPdfDocument
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real ExifInterface API on JPEG and PNG test images. */
@RunWith(AndroidJUnit4::class)
class ImageMetadataInstrumentedTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "pdfr-image-tests-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun <T> onMuPdf(block: () -> T): T = runBlocking(MuPdf.dispatcher) { block() }

    @Test
    fun jpegInfoReadsExifAndXmpAndWritesChanges() {
        val file = image("photo.jpg", Bitmap.CompressFormat.JPEG)
        val exif = ExifInterface(file)
        exif.setAttribute(ExifInterface.TAG_MAKE, "OldMake")
        exif.setAttribute(ExifInterface.TAG_MODEL, "OldModel")
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:06 07:08:09")
        exif.saveAttributes()

        val doc = onMuPdf { MuPdfDocument.open(file, file.name, null) }
        try {
            val info = onMuPdf { doc.info() }
            assertTrue(info.isImage)
            assertEquals("OldMake", info.extraFields.first { it.key == "exif:Make" }.value)
            assertEquals("OldModel", info.extraFields.first { it.key == "exif:Model" }.value)
            assertEquals(
                "2024-05-06T07:08:09",
                info.extraFields.first { it.key == "exif:DateTimeOriginal" }.value,
            )
        } finally {
            onMuPdf { doc.close() }
        }

        val result = ImageMetadata.write(
            file,
            listOf(
                MetadataField("exif:Make", "NewMake"),
                MetadataField(MetadataKeys.XMP_PREFIX + "dc:description", "A caption"),
                MetadataField("exif:GPSLatitude", "52.5"),
                MetadataField("exif:GPSLongitude", "13.25"),
            ),
        )
        assertTrue(result.supported)
        assertTrue(result.complete)

        val values = ImageMetadata.read(file).associateBy { it.key }
        assertEquals("NewMake", values["exif:Make"]?.value)
        assertEquals("A caption", values["xmp:dc:description"]?.value)
        assertEquals("52.5", values["exif:GPSLatitude"]?.value)
        assertEquals("13.25", values["exif:GPSLongitude"]?.value)

        val reopened = onMuPdf { MuPdfDocument.open(file, file.name, null) }
        try {
            val info = onMuPdf { reopened.info() }
            assertEquals("NewMake", info.extraFields.first { it.key == "exif:Make" }.value)
            assertEquals(
                "A caption",
                info.extraFields.first { it.key == "xmp:dc:description" }.value,
            )
        } finally {
            onMuPdf { reopened.close() }
        }
    }

    @Test
    fun pngTextAndTimeChunksAreWrittenAndRead() {
        val file = image("picture.png", Bitmap.CompressFormat.PNG)

        val result = ImageMetadata.write(
            file,
            listOf(
                MetadataField("png:tEXt:Comment", "hello from PDFr"),
                MetadataField("png:tIME", "2026-01-02T03:04:05"),
            ),
        )
        assertTrue(result.supported)
        assertTrue(result.complete)

        val values = ImageMetadata.read(file).associateBy { it.key }
        assertEquals("hello from PDFr", values["png:tEXt:Comment"]?.value)
        assertEquals("2026-01-02T03:04:05", values["png:tIME"]?.value)

        val doc = onMuPdf { MuPdfDocument.open(file, file.name, null) }
        try {
            val info = onMuPdf { doc.info() }
            assertTrue(info.isImage)
            assertNotNull(info.extraFields.firstOrNull { it.key == "png:tEXt:Comment" })
        } finally {
            onMuPdf { doc.close() }
        }
    }

    @Test
    fun unsupportedImageWritesAreReportedForTheSidecar() {
        // A GIF is a valid image for MuPDF but cannot be rewritten by
        // ExifInterface, so the caller must keep the edits beside the file.
        val file = File(dir, "picture.gif").apply {
            writeBytes("GIF89a".toByteArray(Charsets.ISO_8859_1))
        }
        val result = ImageMetadata.write(file, listOf(MetadataField("exif:Make", "NewMake")))
        assertFalse(result.supported)
        assertFalse(result.complete)
    }

    private fun image(name: String, format: Bitmap.CompressFormat): File {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(10, 20, 30))
        val file = File(dir, name)
        file.outputStream().use { output ->
            assertTrue(bitmap.compress(format, 95, output))
        }
        return file
    }
}
