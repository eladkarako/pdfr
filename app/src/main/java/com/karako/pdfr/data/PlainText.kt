package com.karako.pdfr.data

import java.io.File
import java.io.IOException

/**
 * Decodes plain-text files for the reader's custom text view.
 *
 * MuPDF converts text to simple HTML, but that layout cannot provide line
 * numbers, a no-wrap horizontal mode, an RTL alignment switch, or a truly
 * continuous page. The reader therefore reads the original bytes itself and
 * renders them with Compose. The same BOM rules MuPDF uses are honoured here:
 * UTF-8, UTF-16LE/BE and UTF-32LE/BE byte-order marks.
 *
 * Line endings are normalised to `\n`; the original bytes are never changed.
 */
object PlainText {

    fun read(file: File): String? = try {
        decode(file.readBytes())
    } catch (_: IOException) {
        null
    }

    fun decode(bytes: ByteArray): String {
        val (charset, offset) = when {
            startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00) ->
                Charsets.UTF_32LE to 4
            startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF) ->
                Charsets.UTF_32BE to 4
            startsWith(bytes, 0xEF, 0xBB, 0xBF) ->
                Charsets.UTF_8 to 3
            startsWith(bytes, 0xFF, 0xFE) ->
                Charsets.UTF_16LE to 2
            startsWith(bytes, 0xFE, 0xFF) ->
                Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }

        val text = String(bytes, offset, (bytes.size - offset).coerceAtLeast(0), charset)
            .removePrefix("\uFEFF")
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    /**
     * Splits the decoded text into numbered lines. A single trailing newline
     * does not create a phantom last line, but real blank lines inside the
     * file are preserved (and receive their own number).
     */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val parts = text.split('\n')
        return if (parts.size > 1 && parts.last().isEmpty()) {
            parts.dropLast(1)
        } else {
            parts
        }
    }

    /** The number of digits needed by the largest line number, at least one. */
    fun lineNumberDigits(lineCount: Int): Int =
        lineCount.coerceAtLeast(1).toString().length.coerceAtLeast(1)

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        return prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
    }
}
