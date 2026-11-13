package com.karako.pdfr.data

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.util.TimeZone

/** One standard PDF property, which lives in both the Info dictionary and XMP. */
data class StandardPdfField(
    val key: String,
    val infoKey: String,
    val xmpKey: String,
    val isDate: Boolean = false,
)

/** The requested title/author/... slots and the keys they map to in a PDF. */
object PdfMetadataInfo {
    val STANDARD = listOf(
        StandardPdfField(MetadataKeys.TITLE, PdfInfoKeys.TITLE, XmpProperties.DC_TITLE),
        StandardPdfField(MetadataKeys.AUTHOR, PdfInfoKeys.AUTHOR, XmpProperties.DC_CREATOR),
        StandardPdfField(MetadataKeys.SUBJECT, PdfInfoKeys.SUBJECT, XmpProperties.DC_DESCRIPTION),
        StandardPdfField(MetadataKeys.KEYWORDS, PdfInfoKeys.KEYWORDS, XmpProperties.PDF_KEYWORDS),
        StandardPdfField(MetadataKeys.CREATOR, PdfInfoKeys.CREATOR, XmpProperties.XMP_CREATOR_TOOL),
        StandardPdfField(MetadataKeys.PRODUCER, PdfInfoKeys.PRODUCER, XmpProperties.PDF_PRODUCER),
        StandardPdfField(
            MetadataKeys.CREATION_DATE,
            PdfInfoKeys.CREATION_DATE,
            XmpProperties.XMP_CREATE_DATE,
            isDate = true,
        ),
        StandardPdfField(
            MetadataKeys.MODIFIED_DATE,
            PdfInfoKeys.MODIFIED_DATE,
            XmpProperties.XMP_MODIFY_DATE,
            isDate = true,
        ),
    )
}

/**
 * Reads and writes PDF metadata directly, rather than through
 * [Document.getMetaData].
 *
 * MuPDF only exposes the eight standard Info fields through its metadata API.
 * Going through the trailer gives PDFr every Info dictionary key and the
 * catalogue's XMP stream, including properties MuPDF does not name. Writes go
 * through the same objects, so clearing a value deletes it instead of storing
 * an empty string.
 */
object PdfMetadata {

    data class Snapshot(
        val info: Map<String, String> = emptyMap(),
        val xmp: Map<String, String> = emptyMap(),
    )

    data class Update(
        val info: Map<String, String?> = emptyMap(),
        val xmp: Map<String, String?> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = info.isEmpty() && xmp.isEmpty()
    }

    /** The eight standard values, already normalised to the editor's shape. */
    fun standardFields(
        snapshot: Snapshot,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Map<String, String> {
        val values = linkedMapOf<String, String>()
        PdfMetadataInfo.STANDARD.forEach { standard ->
            val raw = snapshot.info[standard.infoKey] ?: snapshot.xmp[standard.xmpKey]
            values[standard.key] = if (standard.isDate) {
                DocumentDates.normalise(raw, timeZone) ?: raw.orEmpty()
            } else {
                raw.orEmpty()
            }
        }
        return values
    }

    fun read(document: Document): Snapshot {
        val pdf = runCatching { document.asPDF() }.getOrNull() ?: return Snapshot()
        val info = readInfo(pdf)
        val xmp = readXmp(pdf)
        // Standard dates may be in either dictionary. Normalise the raw maps so
        // both the schema and the dialog see the same ISO shape.
        val normalisedInfo = info.mapValues { (key, value) ->
            if (PdfMetadataInfo.STANDARD.any { it.infoKey == key && it.isDate }) {
                DocumentDates.normalise(value) ?: value
            } else {
                value
            }
        }
        val normalisedXmp = xmp.mapValues { (key, value) ->
            if (PdfMetadataInfo.STANDARD.any { it.xmpKey == key && it.isDate }) {
                DocumentDates.normalise(value) ?: value
            } else {
                value
            }
        }
        return Snapshot(normalisedInfo, normalisedXmp)
    }

    fun apply(pdf: PDFDocument, update: Update): Boolean {
        var ok = true
        runCatching { applyInfo(pdf, update.info) }.onFailure { ok = false }
        runCatching { applyXmp(pdf, update.xmp) }.onFailure { ok = false }
        return ok
    }

    private fun readInfo(pdf: PDFDocument): Map<String, String> {
        val info = pdf.trailer?.get(PdfDictionaryKeys.INFO)?.resolve() ?: return emptyMap()
        if (!info.isDictionary) return emptyMap()

        val values = linkedMapOf<String, String>()
        for (key in dictionaryKeys(info)) {
            val value = runCatching {
                val objectValue = info.get(key)?.resolve()
                when {
                    objectValue == null || objectValue.isNull -> null
                    objectValue.isString -> objectValue.asString()
                    objectValue.isName -> objectValue.asName()
                    objectValue.isNumber -> objectValue.toString()
                    else -> objectValue.toString()
                }
            }.getOrNull()
            if (!value.isNullOrBlank()) values[key] = value.trim()
        }
        return values
    }

    private fun applyInfo(pdf: PDFDocument, values: Map<String, String?>) {
        if (values.isEmpty()) return
        val trailer = pdf.trailer
        var info = trailer.get(PdfDictionaryKeys.INFO)?.resolve()
        if (info == null || !info.isDictionary) {
            if (values.values.all { it.isNullOrEmpty() }) return
            info = pdf.newDictionary()
            trailer.put(PdfDictionaryKeys.INFO, info)
        }

        values.forEach { (key, value) ->
            if (value.isNullOrEmpty()) {
                info.delete(key)
            } else {
                info.put(key, value)
            }
        }

        if (info.isDictionary && info.size() == 0) {
            trailer.delete(PdfDictionaryKeys.INFO)
        }
    }

    private fun readXmp(pdf: PDFDocument): Map<String, String> {
        val root = pdf.trailer.get(PdfDictionaryKeys.ROOT)?.resolve() ?: return emptyMap()
        val metadata = root.get(PdfDictionaryKeys.METADATA) ?: return emptyMap()
        val bytes = runCatching { metadata.readStream() }.getOrNull() ?: return emptyMap()
        return Xmp.read(bytes.toString(Charsets.UTF_8))
    }

    private fun applyXmp(pdf: PDFDocument, values: Map<String, String?>) {
        if (values.isEmpty()) return
        val trailer = pdf.trailer
        val root = trailer.get(PdfDictionaryKeys.ROOT)?.resolve() ?: return
        val existing = root.get(PdfDictionaryKeys.METADATA)
        val text = runCatching {
            existing?.readStream()?.toString(Charsets.UTF_8)
        }.getOrNull()

        val updated = Xmp.update(text, values)
        if (updated == null) {
            root.delete(PdfDictionaryKeys.METADATA)
            return
        }
        if (updated == text) return

        val dictionary = pdf.newDictionary()
        dictionary.put(PdfDictionaryKeys.TYPE, pdf.newName(PdfDictionaryKeys.METADATA))
        dictionary.put(PdfDictionaryKeys.SUBTYPE, pdf.newName(PdfDictionaryKeys.XML))
        val stream = pdf.addStream(Buffer().apply { writeBytes(updated.toByteArray()) }, dictionary)
        root.put(PdfDictionaryKeys.METADATA, stream)
    }

    private fun dictionaryKeys(dictionary: PDFObject): List<String> {
        val text = runCatching { dictionary.toString() }.getOrNull().orEmpty()
        if (text.isEmpty()) return emptyList()

        val keys = mutableListOf<String>()
        var depth = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '(' -> index = skipLiteralString(text, index)
                '<' -> {
                    if (index + 1 < text.length && text[index + 1] == '<') {
                        depth++
                        index += 2
                    } else {
                        index = skipUntil(text, index, '>')
                    }
                }

                '>' -> {
                    if (index + 1 < text.length && text[index + 1] == '>') {
                        depth = (depth - 1).coerceAtLeast(0)
                        index += 2
                    } else {
                        index++
                    }
                }

                '[' -> {
                    depth++
                    index++
                }

                ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    index++
                }

                '/' -> {
                    val start = index + 1
                    var end = start
                    while (end < text.length &&
                        !text[end].isWhitespace() &&
                        text[end] !in "/[]()<>{}%"
                    ) {
                        end++
                    }
                    if (depth == 1 && end > start) {
                        val key = text.substring(start, end)
                        if (key.isNotEmpty()) keys += key
                    }
                    index = end
                }

                else -> index++
            }
        }
        return keys
    }

    private fun skipLiteralString(text: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                '(' -> {
                    depth++
                    index++
                }

                ')' -> {
                    depth--
                    index++
                    if (depth == 0) return index
                }

                else -> index++
            }
        }
        return text.length
    }

    private fun skipUntil(text: String, start: Int, endChar: Char): Int {
        val end = text.indexOf(endChar, start + 1)
        return if (end < 0) text.length else end + 1
    }
}
