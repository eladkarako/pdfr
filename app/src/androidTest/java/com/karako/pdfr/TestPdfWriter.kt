package com.karako.pdfr

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Writes tiny, valid PDFs without any dependency.
 *
 * The test documents use PDF's built-in Helvetica font, so MuPDF can both
 * render them and extract their text. Each page carries a visible label such
 * as "Fixture page 3", which makes page filtering easy to assert.
 */
object TestPdfWriter {

    fun write(file: File, labels: List<String>) {
        require(labels.isNotEmpty()) { "a fixture needs at least one page" }

        val out = ByteArrayOutputStream()
        val offsets = HashMap<Int, Int>()

        fun emit(text: String) {
            out.write(text.toByteArray(Charsets.ISO_8859_1))
        }

        fun obj(id: Int, body: String) {
            offsets[id] = out.size()
            emit("$id 0 obj\n$body\nendobj\n")
        }

        emit("%PDF-1.4\n")
        emit("%\u00E2\u00E3\u00CF\u00D3\n")

        val firstPage = 4
        val kids = labels.indices.joinToString(" ") { "${firstPage + it * 2} 0 R" }

        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Count ${labels.size} /Kids [$kids] >>")
        obj(
            3,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        )

        labels.forEachIndexed { index, label ->
            val page = firstPage + index * 2
            val content = page + 1
            val bytes = pageContent(label, index + 1, labels.size)
                .toByteArray(Charsets.ISO_8859_1)

            obj(
                page,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 3 0 R >> >> /Contents $content 0 R >>",
            )

            offsets[content] = out.size()
            emit("$content 0 obj\n<< /Length ${bytes.size} >>\nstream\n")
            out.write(bytes)
            emit("\nendstream\nendobj\n")
        }

        val size = 4 + labels.size * 2
        val xref = out.size()

        emit("xref\n0 $size\n")
        emit("0000000000 65535 f \n")
        for (id in 1 until size) {
            emit(String.format(Locale.US, "%010d 00000 n \n", offsets.getValue(id)))
        }
        emit("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")

        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }

    private fun pageContent(label: String, number: Int, count: Int): String = """
        BT
        /F1 30 Tf
        72 700 Td
        (${escape(label)}) Tj
        ET
        BT
        /F1 11 Tf
        72 668 Td
        (PDFr generated fixture - page $number of $count) Tj
        ET
        0.85 w
        48 48 516 696 re
        S
    """.trimIndent()

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)")
}
