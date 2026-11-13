package com.karako.pdfr.data

import java.io.File

/** One IPTC IIM dataset read from a JPEG APP13 segment. */
data class IptcEntry(
    val dataset: Int,
    val name: String,
    val value: String,
)

/**
 * Reads IPTC-IIM metadata from the Photoshop APP13 segment of a JPEG.
 *
 * ExifInterface exposes EXIF and XMP but not IPTC, so this small parser fills
 * the gap. IPTC has no writer here: edits are mirrored into XMP, which every
 * reader of these slots consults first. Unknown datasets are still returned by
 * their numeric id, so a file's full metadata can be listed.
 */
object IptcMetadata {

    private const val PHOTOSHOP_HEADER = "Photoshop 3.0"
    private const val IPTC_RESOURCE_ID = 0x0404

    private val datasetNames = mapOf(
        10 to "Urgency",
        15 to "Category",
        25 to "Keywords",
        40 to "SpecialInstructions",
        55 to "DateCreated",
        60 to "TimeCreated",
        80 to "Creator",
        85 to "CreatorJobTitle",
        90 to "City",
        92 to "Sublocation",
        95 to "ProvinceState",
        101 to "Country",
        103 to "TransmissionReference",
        105 to "Headline",
        110 to "Credit",
        115 to "Source",
        116 to "CopyrightNotice",
        118 to "Contact",
        120 to "Caption",
        122 to "Writer",
    )

    fun readJpeg(file: File): List<IptcEntry> {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
        return readJpeg(bytes)
    }

    fun readJpeg(bytes: ByteArray): List<IptcEntry> {
        if (bytes.size < 4 ||
            (bytes[0].toInt() and 0xFF) != 0xFF ||
            (bytes[1].toInt() and 0xFF) != 0xD8
        ) {
            return emptyList()
        }

        val values = linkedMapOf<Int, MutableList<String>>()
        var index = 2
        while (index + 3 < bytes.size) {
            if ((bytes[index].toInt() and 0xFF) != 0xFF) {
                index++
                continue
            }
            var markerIndex = index + 1
            while (markerIndex < bytes.size && (bytes[markerIndex].toInt() and 0xFF) == 0xFF) {
                markerIndex++
            }
            if (markerIndex >= bytes.size) break
            val marker = bytes[markerIndex].toInt() and 0xFF
            index = markerIndex + 1

            if (marker == 0xD9 || marker == 0xDA) break
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (index + 2 > bytes.size) break

            val length = uint16(bytes, index)
            if (length < 2 || index + length > bytes.size) break
            val start = index + 2
            val end = index + length
            if (marker == 0xED) parseApp13(bytes, start, end, values)
            index = end
        }

        return values.map { (dataset, entries) ->
            IptcEntry(
                dataset = dataset,
                name = datasetNames[dataset] ?: "2:$dataset",
                value = entries.joinToString("; "),
            )
        }
    }

    private fun parseApp13(
        bytes: ByteArray,
        start: Int,
        end: Int,
        values: MutableMap<Int, MutableList<String>>,
    ) {
        var index = start
        val header = PHOTOSHOP_HEADER.toByteArray(Charsets.ISO_8859_1)
        if (end - index > header.size && bytes.copyOfRange(index, index + header.size)
                .contentEquals(header)
        ) {
            index += header.size + 1 // the header is NUL terminated
        }

        while (index + 12 <= end) {
            if (bytes[index] != '8'.code.toByte() ||
                bytes[index + 1] != 'B'.code.toByte() ||
                bytes[index + 2] != 'I'.code.toByte() ||
                bytes[index + 3] != 'M'.code.toByte()
            ) {
                // Not an image resource block; searching forward would risk
                // mistaking photo data for a resource, so stop.
                return
            }
            val resourceId = uint16(bytes, index + 4)
            val nameLength = bytes[index + 6].toInt() and 0xFF
            var nameBlock = 1 + nameLength
            if (nameBlock % 2 != 0) nameBlock++
            val sizeIndex = index + 6 + nameBlock
            if (sizeIndex + 4 > end) return
            val size = int(bytes, sizeIndex)
            if (size < 0) return
            val dataStart = sizeIndex + 4
            val dataEnd = dataStart + size
            if (dataEnd > end) return

            if (resourceId == IPTC_RESOURCE_ID) parseIptc(bytes, dataStart, dataEnd, values)

            val padded = if (size % 2 == 0) size else size + 1
            index = dataStart + padded
        }
    }

    private fun parseIptc(
        bytes: ByteArray,
        start: Int,
        end: Int,
        values: MutableMap<Int, MutableList<String>>,
    ) {
        var index = start
        while (index + 5 <= end) {
            // A dataset starts with the IIM marker 0x1C.
            if ((bytes[index].toInt() and 0xFF) != 0x1C) break
            val dataset = bytes[index + 2].toInt() and 0xFF
            var size = uint16(bytes, index + 3)
            var dataStart = index + 5
            if (size and 0x8000 != 0) {
                val lengthBytes = size and 0x7FFF
                if (lengthBytes !in 1..4 || dataStart + lengthBytes > end) break
                size = 0
                for (offset in 0 until lengthBytes) {
                    size = (size shl 8) or (bytes[dataStart + offset].toInt() and 0xFF)
                }
                dataStart += lengthBytes
            }
            if (size < 0 || dataStart + size > end) break

            val value = decode(bytes, dataStart, size).trim()
            if (value.isNotEmpty()) {
                values.getOrPut(dataset) { mutableListOf() } += value
            }
            index = dataStart + size
        }
    }

    private fun decode(bytes: ByteArray, start: Int, length: Int): String {
        val text = String(bytes, start, length, Charsets.UTF_8)
        return if (text.contains('\uFFFD')) {
            String(bytes, start, length, Charsets.ISO_8859_1)
        } else {
            text
        }.trimEnd('\u0000')
    }

    private fun uint16(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)

    private fun int(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xFF) shl 24) or
                ((bytes[index + 1].toInt() and 0xFF) shl 16) or
                ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                (bytes[index + 3].toInt() and 0xFF)
}
