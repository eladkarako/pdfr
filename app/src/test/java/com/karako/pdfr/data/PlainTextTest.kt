package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {

    @Test
    fun decodesUtf8AndStripsTheBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "hello".toByteArray(Charsets.UTF_8)
        assertEquals("hello", PlainText.decode(bytes))
    }

    @Test
    fun decodesUtf16LittleEndian() {
        val bytes = byteArrayOf(
            0xFF.toByte(),
            0xFE.toByte(),
            'H'.code.toByte(),
            0x00,
            'i'.code.toByte(),
            0x00
        )
        assertEquals("Hi", PlainText.decode(bytes))
    }

    @Test
    fun decodesUtf16BigEndian() {
        val bytes = byteArrayOf(
            0xFE.toByte(),
            0xFF.toByte(),
            0x00,
            'H'.code.toByte(),
            0x00,
            'i'.code.toByte()
        )
        assertEquals("Hi", PlainText.decode(bytes))
    }

    @Test
    fun normalisesWindowsAndClassicMacLineEndings() {
        assertEquals("a\nb\nc", PlainText.decode("a\r\nb\rc".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun splitsLinesWithoutAPhantomTrailingLine() {
        assertEquals(listOf(""), PlainText.lines(""))
        assertEquals(listOf("a"), PlainText.lines("a\n"))
        assertEquals(listOf("a", "b"), PlainText.lines("a\nb"))
        assertEquals(listOf("a", ""), PlainText.lines("a\n\n"))
        assertEquals(listOf("", "a"), PlainText.lines("\na"))
    }

    @Test
    fun copyTextDropsOnlyThePhantomTrailingLine() {
        assertEquals("", PlainText.copyText(""))
        assertEquals("a", PlainText.copyText("a\n"))
        assertEquals("a\nb", PlainText.copyText("a\nb\n"))
        assertEquals("a\n\nb", PlainText.copyText("a\n\nb"))
        assertEquals("\na", PlainText.copyText("\na"))
        assertEquals("a\n", PlainText.copyText("a\n\n"))
    }

    @Test
    fun copyTextKeepsTheWholeDocumentEvenWhenItIsLong() {
        val lines = (1..500).map { "L%03d".format(it) }
        val text = lines.joinToString("\n") + "\n"
        val copied = PlainText.copyText(text)

        assertEquals(500, copied.lines().size)
        assertEquals("L001", copied.lines().first())
        assertEquals("L500", copied.lines().last())
    }

    @Test
    fun countsLineNumberDigits() {
        assertEquals(1, PlainText.lineNumberDigits(0))
        assertEquals(1, PlainText.lineNumberDigits(1))
        assertEquals(1, PlainText.lineNumberDigits(9))
        assertEquals(2, PlainText.lineNumberDigits(10))
        assertEquals(3, PlainText.lineNumberDigits(100))
    }
}
