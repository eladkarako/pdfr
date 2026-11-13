package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Format-level tests for the image metadata readers. They run on the JVM
 * because PngMetadata, IptcMetadata, IccProfile and Xmp do not depend on
 * Android classes.
 */
class ImageMetadataFormatsTest {

    private val directory: File = createTempDir()

    private fun createTempDir(): File {
        val dir =
            File(System.getProperty("java.io.tmpdir"), "pdfr-image-tests-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun file(name: String, bytes: ByteArray): File =
        File(directory, name).apply { writeBytes(bytes) }

    @Test
    fun pngTextPhysicalAndTimeChunksRoundTrip() {
        val png = file(
            "sample.png",
            png(
                textChunk("Software", "PDFr"),
                timeChunk(2025, 12, 31, 23, 58, 59),
            ),
        )

        val before = PngMetadata.read(png).associateBy { it.key }
        assertEquals("PDFr", before["png:tEXt:Software"]?.value)
        assertEquals("2025-12-31T23:58:59", before["png:tIME"]?.value)

        val written = PngMetadata.write(
            png,
            listOf(
                MetadataField("png:tEXt:Software", "PDFr 26.11.11"),
                MetadataField("png:tEXt:Comment", "hello"),
                MetadataField("png:pHYs", "300 x 300, unit 1"),
                MetadataField("png:gAMA", "0.45455"),
                MetadataField("png:tIME", "2026-01-02T03:04:05"),
            ),
            utc,
        )
        assertTrue(written)

        val after = PngMetadata.read(png).associateBy { it.key }
        assertEquals("PDFr 26.11.11", after["png:tEXt:Software"]?.value)
        assertEquals("hello", after["png:tEXt:Comment"]?.value)
        assertEquals("300 x 300, unit 1", after["png:pHYs"]?.value)
        assertEquals("0.45455", after["png:gAMA"]?.value)
        assertEquals("2026-01-02T03:04:05", after["png:tIME"]?.value)
    }

    @Test
    fun pngEmptyValueDropsTheChunk() {
        val png = file("drop.png", png(textChunk("Comment", "remove me")))

        assertTrue(PngMetadata.write(png, listOf(MetadataField("png:tEXt:Comment", "")), utc))

        assertTrue(PngMetadata.read(png).none { it.key == "png:tEXt:Comment" })
    }

    @Test
    fun iptcReadsKeywordsAndCaption() {
        val jpeg = file(
            "iptc.jpg",
            jpeg(
                app13(
                    iptcDataset(2, 25, "first"),
                    iptcDataset(2, 25, "second"),
                    iptcDataset(2, 120, "A caption"),
                    iptcDataset(2, 80, "A photographer"),
                ),
            ),
        )

        val entries = IptcMetadata.readJpeg(jpeg).associateBy { it.dataset }

        assertEquals("first; second", entries[25]?.value)
        assertEquals("A caption", entries[120]?.value)
        assertEquals("A photographer", entries[80]?.value)
    }

    @Test
    fun iccProfileDescriptionIsReadFromJpegAndPng() {
        val profile = iccProfile("sRGB IEC61966-2.1")
        val jpeg = file("icc.jpg", jpeg(app2(profile)))
        assertEquals("sRGB IEC61966-2.1", IccProfile.label(jpeg))

        val png = file("icc.png", png(iccChunk(profile)))
        assertEquals("sRGB IEC61966-2.1", IccProfile.label(png))
    }

    @Test
    fun xmpUpdateRemovesDuplicatePropertiesAcrossBlocks() {
        val text = """
            <?xml version="1.0" encoding="UTF-8"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Old</rdf:li></rdf:Alt></dc:title>
                </rdf:Description>
                <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:xmp="http://ns.adobe.com/xap/1.0/">
                  <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Duplicate</rdf:li></rdf:Alt></dc:title>
                  <xmp:Label>Green</xmp:Label>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val updated = Xmp.update(text, mapOf("dc:title" to "New title"))
        assertNotNull(updated)

        val values = Xmp.read(updated!!)
        assertEquals("New title", values["dc:title"])
        assertEquals("Green", values["xmp:Label"])
        assertEquals(1, Regex("<dc:title").findAll(updated).count())
    }

    @Test
    fun xmpUpdateCanCreateADocumentFromNothing() {
        val updated = Xmp.update(null, mapOf("dc:creator" to "Alice"))
        val values = Xmp.read(updated!!)
        assertEquals("Alice", values["dc:creator"])
    }

    @Test
    fun xmpUpdateRemovesEveryPropertyAndReturnsNull() {
        val text = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
                  <xmp:Label>Green</xmp:Label>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        assertEquals(null, Xmp.update(text, mapOf("xmp:Label" to "")))
    }

    private val utc = java.util.TimeZone.getTimeZone("UTC")

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            ),
        )
        val header = ByteArrayOutputStream().apply {
            writeInt(1)
            writeInt(1)
            write(8)
            write(2)
            write(0)
            write(0)
            write(0)
        }.toByteArray()
        out.write(pngChunk("IHDR", header))
        chunks.forEach(out::write)
        out.write(pngChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        return ByteArrayOutputStream().apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
        }.toByteArray()
    }

    private fun textChunk(keyword: String, value: String): ByteArray =
        pngChunk(
            "tEXt",
            ByteArrayOutputStream().apply {
                write(keyword.toByteArray(Charsets.ISO_8859_1))
                write(0)
                write(value.toByteArray(Charsets.ISO_8859_1))
            }.toByteArray(),
        )

    private fun timeChunk(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): ByteArray =
        pngChunk(
            "tIME",
            ByteArrayOutputStream().apply {
                write((year shr 8) and 0xFF)
                write(year and 0xFF)
                write(month)
                write(day)
                write(hour)
                write(minute)
                write(second)
            }.toByteArray(),
        )

    private fun iccChunk(profile: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().apply {
            java.util.zip.DeflaterOutputStream(this).use { it.write(profile) }
        }.toByteArray()
        return pngChunk(
            "iCCP",
            ByteArrayOutputStream().apply {
                write("ICC".toByteArray(Charsets.ISO_8859_1))
                write(0)
                write(0)
                write(compressed)
            }.toByteArray(),
        )
    }

    private fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(0xD8)
        segments.forEach(out::write)
        out.write(0xFF)
        out.write(0xD9)
        return out.toByteArray()
    }

    private fun app2(profile: ByteArray): ByteArray {
        val data = ByteArrayOutputStream().apply {
            write("ICC_PROFILE".toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(1)
            write(1)
            write(profile)
        }.toByteArray()
        return jpegSegment(0xE2, data)
    }

    private fun app13(vararg iptc: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write("Photoshop 3.0".toByteArray(Charsets.ISO_8859_1))
            write(0)
            write("8BIM".toByteArray(Charsets.ISO_8859_1))
            write(0x04)
            write(0x04)
            write(0) // empty Pascal name, padded to an even length
            write(0)
            val data = iptc.fold(ByteArray(0)) { acc, item -> acc + item }
            writeInt(data.size)
            write(data)
            if (data.size % 2 != 0) write(0)
        }.toByteArray()
        return jpegSegment(0xED, payload)
    }

    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(0xFF)
            write(marker)
            write((payload.size + 2) shr 8)
            write((payload.size + 2) and 0xFF)
            write(payload)
        }.toByteArray()

    private fun iptcDataset(record: Int, dataset: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            write(0x1C)
            write(record)
            write(dataset)
            write((bytes.size shr 8) and 0xFF)
            write(bytes.size and 0xFF)
            write(bytes)
        }.toByteArray()
    }

    private fun iccProfile(description: String): ByteArray {
        val text = description.toByteArray(Charsets.ISO_8859_1)
        val descTagSize = 12 + text.size + 1
        val profile = ByteArray(128 + 4 + 12 + descTagSize)
        val tagCount = ByteArrayOutputStream().apply { writeInt(1) }.toByteArray()
        tagCount.copyInto(profile, 128)
        "desc".toByteArray(Charsets.ISO_8859_1).copyInto(profile, 132)
        ByteArrayOutputStream().apply { writeInt(144) }.toByteArray().copyInto(profile, 136)
        ByteArrayOutputStream().apply { writeInt(descTagSize) }.toByteArray().copyInto(profile, 140)
        "desc".toByteArray(Charsets.ISO_8859_1).copyInto(profile, 144)
        ByteArrayOutputStream().apply { writeInt(text.size + 1) }.toByteArray()
            .copyInto(profile, 152)
        text.copyInto(profile, 156)
        return profile
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }
}
