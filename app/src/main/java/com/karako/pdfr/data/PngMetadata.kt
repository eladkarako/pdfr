package com.karako.pdfr.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Reads and writes the PNG chunks that carry human-readable metadata.
 *
 * ExifInterface already handles the `eXIf` and XMP `iTXt` chunks. This class
 * covers the chunks it does not expose: `tEXt`, `iTXt` (decompressed only for
 * reading), `pHYs`, `gAMA` and `tIME`.
 *
 * The writer keeps every chunk it does not recognise byte-for-byte and rebuilds
 * the CRC of the chunks it replaces.
 */
object PngMetadata {

    private const val TEXT_PREFIX = MetadataKeys.PNG_PREFIX + "tEXt:"
    private const val ITXT_PREFIX = MetadataKeys.PNG_PREFIX + "iTXt:"
    private const val PHYS_KEY = MetadataKeys.PNG_PREFIX + "pHYs"
    private const val GAMA_KEY = MetadataKeys.PNG_PREFIX + "gAMA"
    private const val TIME_KEY = MetadataKeys.PNG_PREFIX + "tIME"

    private const val XMP_KEYWORD = "XML:com.adobe.xmp"
    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun isPng(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val head = ByteArray(pngSignature.size)
            stream.read(head) == head.size && head.contentEquals(pngSignature)
        }
    }.getOrDefault(false)

    fun read(file: File): List<MetadataField> {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
        if (!isPng(bytes)) return emptyList()

        val fields = mutableListOf<MetadataField>()
        var index = pngSignature.size
        while (index + 8 <= bytes.size) {
            val length = intAt(bytes, index)
            if (length < 0 || index + 12L + length > bytes.size) break
            val type = String(bytes, index + 4, 4, Charsets.ISO_8859_1)
            val dataStart = index + 8
            when (type) {
                "tEXt" -> {
                    val terminator = indexOfNull(bytes, dataStart, dataStart + length)
                    if (terminator > dataStart) {
                        val keyword = text(bytes, dataStart, terminator - dataStart)
                        val value = text(bytes, terminator + 1, dataStart + length - terminator - 1)
                        if (keyword.isNotEmpty()) fields += MetadataField(TEXT_PREFIX + keyword, value)
                    }
                }

                "iTXt" -> {
                    val chunk = parseITxt(bytes, dataStart, length)
                    if (chunk != null && chunk.first.isNotEmpty() && chunk.first != XMP_KEYWORD) {
                        fields += MetadataField(ITXT_PREFIX + chunk.first, chunk.second)
                    }
                }

                "pHYs" -> if (length >= 9) {
                    val x = intAt(bytes, dataStart)
                    val y = intAt(bytes, dataStart + 4)
                    val unit = bytes[dataStart + 8].toInt() and 0xFF
                    fields += MetadataField(PHYS_KEY, "$x x $y, unit $unit")
                }

                "gAMA" -> if (length >= 4) {
                    val raw = intAt(bytes, dataStart)
                    fields += MetadataField(GAMA_KEY, formatDecimal(raw / 100000.0))
                }

                "tIME" -> if (length >= 7) {
                    val year = uint16At(bytes, dataStart)
                    val month = bytes[dataStart + 2].toInt() and 0xFF
                    val day = bytes[dataStart + 3].toInt() and 0xFF
                    val hour = bytes[dataStart + 4].toInt() and 0xFF
                    val minute = bytes[dataStart + 5].toInt() and 0xFF
                    val second = bytes[dataStart + 6].toInt() and 0xFF
                    fields += MetadataField(
                        TIME_KEY,
                        String.format(
                            java.util.Locale.US,
                            "%04d-%02d-%02dT%02d:%02d:%02d",
                            year, month, day, hour, minute, second,
                        ),
                        MetadataValueKind.DATE_TIME,
                    )
                }
            }
            index = dataStart + length + 4
        }
        return fields
    }

    /** Applies the `png:` fields, returning false when the file is not a PNG. */
    fun write(file: File, fields: List<MetadataField>, timeZone: TimeZone = TimeZone.getDefault()): Boolean {
        val updates = fields
            .filter { it.key.startsWith(MetadataKeys.PNG_PREFIX) }
            .associate { it.key to it.value.trim() }
        if (updates.isEmpty()) return true

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (!isPng(bytes)) return false

        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, pngSignature.size)
        val written = mutableSetOf<String>()
        var index = pngSignature.size
        var sawEnd = false

        while (index + 8 <= bytes.size) {
            val length = intAt(bytes, index)
            if (length < 0 || index + 12L + length > bytes.size) return false
            val type = String(bytes, index + 4, 4, Charsets.ISO_8859_1)
            val dataStart = index + 8
            val chunkEnd = dataStart + length + 4

            if (type == "IEND") {
                writePending(output, updates, written, timeZone)
                output.write(bytes, index, chunkEnd - index)
                sawEnd = true
                break
            }

            val key = fieldKey(type, bytes, dataStart, length)
            if (key != null && updates.containsKey(key)) {
                written += key
                val value = updates.getValue(key)
                // An empty value drops the chunk entirely.
                if (value.isNotEmpty()) {
                    val replacement = encode(type, key, value, timeZone)
                    if (replacement != null) output.write(chunk(type, replacement))
                }
            } else {
                output.write(bytes, index, chunkEnd - index)
            }
            index = chunkEnd
        }
        if (!sawEnd) return false

        val temp = File(file.parentFile, "${file.name}.part")
        return runCatching {
            temp.writeBytes(output.toByteArray())
            if (file.exists() && !file.delete()) return false
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            true
        }.getOrDefault(false)
    }

    private fun writePending(
        output: ByteArrayOutputStream,
        updates: Map<String, String>,
        written: MutableSet<String>,
        timeZone: TimeZone,
    ) {
        updates.forEach { (key, value) ->
            if (key in written) return@forEach
            val type = when {
                key.startsWith(TEXT_PREFIX) -> "tEXt"
                key.startsWith(ITXT_PREFIX) -> "iTXt"
                key == PHYS_KEY -> "pHYs"
                key == GAMA_KEY -> "gAMA"
                key == TIME_KEY -> "tIME"
                else -> return@forEach
            }
            if (value.isEmpty()) return@forEach
            val data = encode(type, key, value, timeZone) ?: return@forEach
            output.write(chunk(type, data))
        }
    }

    private fun fieldKey(type: String, bytes: ByteArray, start: Int, length: Int): String? = when (type) {
        "tEXt" -> {
            val terminator = indexOfNull(bytes, start, start + length)
            if (terminator > start) TEXT_PREFIX + text(bytes, start, terminator - start) else null
        }

        "iTXt" -> parseITxt(bytes, start, length)?.first
            ?.takeIf { it.isNotEmpty() && it != XMP_KEYWORD }
            ?.let { ITXT_PREFIX + it }

        "pHYs" -> PHYS_KEY
        "gAMA" -> GAMA_KEY
        "tIME" -> TIME_KEY
        else -> null
    }

    private fun encode(
        type: String,
        key: String,
        value: String,
        timeZone: TimeZone,
    ): ByteArray? = when (type) {
        "tEXt" -> {
            val keyword = key.removePrefix(TEXT_PREFIX).take(79)
            if (keyword.isEmpty()) return null
            val text = value.toByteArray(Charsets.ISO_8859_1)
            ByteArrayOutputStream().apply {
                write(keyword.toByteArray(Charsets.ISO_8859_1))
                write(0)
                write(text)
            }.toByteArray()
        }

        "iTXt" -> {
            val keyword = key.removePrefix(ITXT_PREFIX).take(79)
            if (keyword.isEmpty()) return null
            ByteArrayOutputStream().apply {
                write(keyword.toByteArray(Charsets.ISO_8859_1))
                write(0) // keyword terminator
                write(0) // compression flag: uncompressed
                write(0) // compression method
                write(0) // empty language tag
                write(0) // empty translated keyword
                write(value.toByteArray(Charsets.UTF_8))
            }.toByteArray()
        }

        "pHYs" -> {
            val numbers = Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
            val x = numbers.getOrNull(0) ?: return null
            val y = numbers.getOrNull(1) ?: x
            val unit = numbers.getOrNull(2)?.coerceIn(0, 1) ?: 1
            ByteArrayOutputStream().apply {
                writeInt(x)
                writeInt(y)
                write(unit)
            }.toByteArray()
        }

        "gAMA" -> {
            val gamma = value.toDoubleOrNull() ?: return null
            val raw = (gamma * 100000.0).toLong().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            ByteArrayOutputStream().apply { writeInt(raw) }.toByteArray()
        }

        "tIME" -> {
            val millis = DocumentDates.parseFlexible(value, timeZone) ?: return null
            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
            ByteArrayOutputStream().apply {
                write((date.year shr 8) and 0xFF)
                write(date.year and 0xFF)
                write(date.monthValue)
                write(date.dayOfMonth)
                write(date.hour)
                write(date.minute)
                write(date.second)
            }.toByteArray()
        }

        else -> null
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        return ByteArrayOutputStream().apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
        }.toByteArray()
    }

    private fun parseITxt(bytes: ByteArray, start: Int, length: Int): Pair<String, String>? {
        val end = (start + length).coerceAtMost(bytes.size)
        val keywordEnd = indexOfNull(bytes, start, end)
        if (keywordEnd <= start) return null
        val keyword = text(bytes, start, keywordEnd - start)

        var index = keywordEnd + 1
        if (index + 2 > end) return null
        val compressed = (bytes[index].toInt() and 0xFF) != 0
        index += 2 // compression flag and method

        val languageEnd = indexOfNull(bytes, index, end)
        if (languageEnd < 0) return null
        index = languageEnd + 1
        val translatedEnd = indexOfNull(bytes, index, end)
        if (translatedEnd < 0) return null
        index = translatedEnd + 1
        if (index > end) return null

        val raw = bytes.copyOfRange(index, end)
        val value = if (compressed) {
            runCatching {
                InflaterInputStream(raw.inputStream(), Inflater()).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
            }.getOrNull() ?: return null
        } else {
            raw.toString(Charsets.UTF_8)
        }
        return keyword to value
    }

    private fun text(bytes: ByteArray, start: Int, length: Int): String {
        if (start < 0 || length <= 0 || start + length > bytes.size) return ""
        return String(bytes, start, length, Charsets.ISO_8859_1)
    }

    private fun indexOfNull(bytes: ByteArray, start: Int, end: Int): Int {
        var index = start.coerceAtLeast(0)
        val limit = end.coerceAtMost(bytes.size)
        while (index < limit) {
            if (bytes[index].toInt() == 0) return index
            index++
        }
        return -1
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= pngSignature.size &&
            bytes.copyOfRange(0, pngSignature.size).contentEquals(pngSignature)

    private fun intAt(bytes: ByteArray, index: Int): Int {
        if (index + 4 > bytes.size) return -1
        return ((bytes[index].toInt() and 0xFF) shl 24) or
            ((bytes[index + 1].toInt() and 0xFF) shl 16) or
            ((bytes[index + 2].toInt() and 0xFF) shl 8) or
            (bytes[index + 3].toInt() and 0xFF)
    }

    private fun uint16At(bytes: ByteArray, index: Int): Int {
        if (index + 2 > bytes.size) return 0
        return ((bytes[index].toInt() and 0xFF) shl 8) or
            (bytes[index + 1].toInt() and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun formatDecimal(value: Double): String =
        java.math.BigDecimal.valueOf(value)
            .stripTrailingZeros()
            .toPlainString()
}
