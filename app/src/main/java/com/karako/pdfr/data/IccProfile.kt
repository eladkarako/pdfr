package com.karako.pdfr.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.InflaterInputStream

/**
 * Reads the embedded ICC colour profile's description.
 *
 * ExifInterface does not expose ICC profiles, so the profile is pulled out of
 * the JPEG APP2 marker, the PNG `iCCP` chunk or the WebP `ICCP` chunk and its
 * `desc`/`mluc` tag is decoded. Writing a profile is not attempted; edits to
 * the ICC slot are kept in the sidecar.
 */
object IccProfile {

    fun label(file: File): String? = read(file)?.description

    fun read(file: File): IccInfo? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val profile = when {
            isJpeg(bytes) -> jpegProfile(bytes)
            isPng(bytes) -> pngProfile(bytes)
            isWebp(bytes) -> webpProfile(bytes)
            else -> null
        } ?: return null
        return IccInfo(describe(profile), profile.size)
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
                (bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xFF) == 0xD8

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
                (bytes[0].toInt() and 0xFF) == 0x89 &&
                bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() &&
                bytes[3] == 'G'.code.toByte()

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"

    private fun jpegProfile(bytes: ByteArray): ByteArray? {
        val chunks = mutableMapOf<Int, ByteArray>()
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
            if (marker == 0xE2) {
                val start = index + 2
                val header = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)
                if (start + header.size + 2 <= index + length &&
                    bytes.copyOfRange(start, start + header.size).contentEquals(header)
                ) {
                    val sequence = bytes[start + header.size].toInt() and 0xFF
                    val dataStart = start + header.size + 2
                    chunks[sequence] = bytes.copyOfRange(dataStart, index + length)
                }
            }
            index += length
        }
        if (chunks.isEmpty()) return null
        return ByteArrayOutputStream().apply {
            chunks.keys.sorted().forEach { write(chunks.getValue(it)) }
        }.toByteArray()
    }

    private fun pngProfile(bytes: ByteArray): ByteArray? {
        var index = 8
        while (index + 8 <= bytes.size) {
            val length = int(bytes, index)
            if (length < 0 || index + 12L + length > bytes.size) break
            val type = String(bytes, index + 4, 4, Charsets.ISO_8859_1)
            if (type == "iCCP") {
                val start = index + 8
                val end = start + length
                var nameEnd = start
                while (nameEnd < end && bytes[nameEnd].toInt() != 0) nameEnd++
                if (nameEnd + 2 > end) return null
                val compressed = bytes.copyOfRange(nameEnd + 2, end)
                return runCatching {
                    InflaterInputStream(compressed.inputStream()).use { it.readBytes() }
                }.getOrNull()
            }
            index = start(index) + length + 4
        }
        return null
    }

    private fun webpProfile(bytes: ByteArray): ByteArray? {
        var index = 12
        while (index + 8 <= bytes.size) {
            val type = String(bytes, index, 4, Charsets.ISO_8859_1)
            val size = littleInt(bytes, index + 4)
            if (size < 0 || index + 8L + size > bytes.size) break
            if (type == "ICCP") {
                return bytes.copyOfRange(index + 8, index + 8 + size)
            }
            index += 8 + size + (size and 1)
        }
        return null
    }

    private fun describe(profile: ByteArray): String? {
        if (profile.size < 132) return null
        val tagCount = int(profile, 128)
        if (tagCount <= 0 || tagCount > 4096) return null

        var fallback: String? = null
        for (index in 0 until tagCount) {
            val entry = 132 + index * 12
            if (entry + 12 > profile.size) break
            val signature = String(profile, entry, 4, Charsets.ISO_8859_1)
            if (signature != "desc") continue
            val offset = int(profile, entry + 4)
            val size = int(profile, entry + 8)
            if (offset < 0 || size <= 0 || offset + size > profile.size) continue
            val text = descriptionText(profile, offset, size)
            if (text.isNullOrBlank()) continue
            if (index == 0) return text
            fallback = text
            // Keep looking for an English record, but any description is
            // better than the size-only fallback below.
        }
        return fallback
    }

    private fun descriptionText(profile: ByteArray, offset: Int, size: Int): String? {
        if (size < 8) return null
        val type = String(profile, offset, 4, Charsets.ISO_8859_1)
        return when (type) {
            "desc" -> {
                if (size < 12) return null
                val count = int(profile, offset + 8)
                if (count <= 0 || offset + 12 + count > profile.size) return null
                String(profile, offset + 12, count, Charsets.ISO_8859_1)
                    .trimEnd('\u0000')
                    .trim()
            }

            "mluc" -> {
                if (size < 16) return null
                val count = int(profile, offset + 8)
                val recordSize = int(profile, offset + 12)
                if (count <= 0 || recordSize < 12 || count > 4096) return null
                var best: String? = null
                for (index in 0 until count) {
                    val record = offset + 16 + index * recordSize
                    if (record + 12 > profile.size) break
                    val language = String(profile, record, 2, Charsets.ISO_8859_1)
                    val country = String(profile, record + 2, 2, Charsets.ISO_8859_1)
                    val length = int(profile, record + 4)
                    val stringOffset = int(profile, record + 8)
                    if (length <= 0) continue
                    val start = offset + stringOffset
                    if (start < 0 || start + length > profile.size) continue
                    val text = String(profile, start, length, Charsets.UTF_16BE)
                        .trimEnd('\u0000')
                        .trim()
                    if (text.isEmpty()) continue
                    if (language.equals("en", ignoreCase = true) ||
                        country.equals("US", ignoreCase = true)
                    ) {
                        return text
                    }
                    if (best == null) best = text
                }
                best
            }

            "text" -> {
                if (size < 8) return null
                String(profile, offset + 8, size - 8, Charsets.ISO_8859_1)
                    .trimEnd('\u0000')
                    .trim()
            }

            else -> null
        }
    }

    private fun int(bytes: ByteArray, index: Int): Int {
        if (index + 4 > bytes.size) return -1
        return ((bytes[index].toInt() and 0xFF) shl 24) or
                ((bytes[index + 1].toInt() and 0xFF) shl 16) or
                ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                (bytes[index + 3].toInt() and 0xFF)
    }

    private fun littleInt(bytes: ByteArray, index: Int): Int {
        if (index + 4 > bytes.size) return -1
        return (bytes[index].toInt() and 0xFF) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                ((bytes[index + 2].toInt() and 0xFF) shl 16) or
                ((bytes[index + 3].toInt() and 0xFF) shl 24)
    }

    private fun uint16(bytes: ByteArray, index: Int): Int {
        if (index + 2 > bytes.size) return 0
        return ((bytes[index].toInt() and 0xFF) shl 8) or
                (bytes[index + 1].toInt() and 0xFF)
    }

    private fun start(index: Int) = index + 8
}

/** [description] is the human-readable name stored in the profile. */
data class IccInfo(
    val description: String?,
    val sizeBytes: Int,
)
