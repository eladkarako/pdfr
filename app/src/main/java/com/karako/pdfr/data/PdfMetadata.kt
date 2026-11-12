package com.karako.pdfr.data

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import org.w3c.dom.Document as XmlDocument
import org.w3c.dom.Attr
import org.w3c.dom.Element
import java.io.StringWriter
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

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
        StandardPdfField(MetadataKeys.TITLE, "Title", "dc:title"),
        StandardPdfField(MetadataKeys.AUTHOR, "Author", "dc:creator"),
        StandardPdfField(MetadataKeys.SUBJECT, "Subject", "dc:description"),
        StandardPdfField(MetadataKeys.KEYWORDS, "Keywords", "pdf:Keywords"),
        StandardPdfField(MetadataKeys.CREATOR, "Creator", "xmp:CreatorTool"),
        StandardPdfField(MetadataKeys.PRODUCER, "Producer", "pdf:Producer"),
        StandardPdfField(
            MetadataKeys.CREATION_DATE,
            "CreationDate",
            "xmp:CreateDate",
            isDate = true,
        ),
        StandardPdfField(
            MetadataKeys.MODIFIED_DATE,
            "ModDate",
            "xmp:ModifyDate",
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

    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
    private const val ADOBE_NS = "adobe:ns:meta/"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/"
    private const val PDF_NS = "http://ns.adobe.com/pdf/1.3/"
    private const val PHOTOSHOP_NS = "http://ns.adobe.com/photoshop/1.0/"
    private const val XMP_MM_NS = "http://ns.adobe.com/xap/1.0/mm/"
    private const val XMP_RIGHTS_NS = "http://ns.adobe.com/xap/1.0/rights/"
    private const val TIFF_NS = "http://ns.adobe.com/tiff/1.0/"
    private const val EXIF_NS = "http://ns.adobe.com/exif/1.0/"
    private const val IPTC_NS = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"

    private val namespaces = mapOf(
        "dc" to DC_NS,
        "xmp" to XMP_NS,
        "pdf" to PDF_NS,
        "photoshop" to PHOTOSHOP_NS,
        "xmpMM" to XMP_MM_NS,
        "xmpRights" to XMP_RIGHTS_NS,
        "tiff" to TIFF_NS,
        "exif" to EXIF_NS,
        "Iptc4xmpCore" to IPTC_NS,
    )

    /** The eight standard values, already normalised to the editor's shape. */
    fun standardFields(snapshot: Snapshot, timeZone: TimeZone = TimeZone.getDefault()): Map<String, String> {
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
        val info = pdf.trailer?.get("Info")?.resolve() ?: return emptyMap()
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
                    objectValue.isArray -> objectValue.toString()
                    objectValue.isDictionary -> objectValue.toString()
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
        var info = trailer.get("Info")?.resolve()
        if (info == null || !info.isDictionary) {
            if (values.values.all { it.isNullOrEmpty() }) return
            info = pdf.newDictionary()
            trailer.put("Info", info)
        }

        values.forEach { (key, value) ->
            if (value.isNullOrEmpty()) {
                info.delete(key)
            } else {
                info.put(key, value)
            }
        }

        if (info.isDictionary && info.size() == 0) {
            trailer.delete("Info")
        }
    }

    private fun readXmp(pdf: PDFDocument): Map<String, String> {
        val root = pdf.trailer.get("Root")?.resolve() ?: return emptyMap()
        val metadata = root.get("Metadata") ?: return emptyMap()
        val bytes = runCatching { metadata.readStream() }.getOrNull() ?: return emptyMap()
        val xml = runCatching { parseXml(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return emptyMap()

        val values = linkedMapOf<String, String>()
        val descriptions = xml.getElementsByTagNameNS(RDF_NS, "Description")
        for (index in 0 until descriptions.length) {
            val description = descriptions.item(index) as? Element ?: continue

            val attributes = description.attributes
            for (i in 0 until attributes.length) {
                val attribute = attributes.item(i)
                val prefix = attribute.prefix ?: continue
                if (prefix == "xmlns" || prefix == "xml" || prefix == "rdf") continue
                val qname = "$prefix:${attribute.localName ?: attribute.nodeName.substringAfter(':')}"
                val value = attribute.nodeValue?.trim().orEmpty()
                if (value.isNotEmpty()) values[qname] = value
            }

            val children = description.childNodes
            for (i in 0 until children.length) {
                val element = children.item(i) as? Element ?: continue
                if (element.namespaceURI == RDF_NS) continue
                val prefix = element.prefix ?: continue
                val qname = "$prefix:${element.localName ?: element.nodeName.substringAfter(':')}"
                val value = elementValue(element)
                if (value.isNotEmpty()) values[qname] = value
            }
        }
        return values
    }

    private fun applyXmp(pdf: PDFDocument, values: Map<String, String?>) {
        if (values.isEmpty()) return
        val trailer = pdf.trailer
        val root = trailer.get("Root")?.resolve() ?: return
        val existing = root.get("Metadata")
        val bytes = runCatching { existing?.readStream() }.getOrNull()

        val existingXml = bytes?.let {
            runCatching { parseXml(it.toString(Charsets.UTF_8)) }.getOrNull()
        }
        if (existingXml == null && values.values.all { it.isNullOrEmpty() }) return

        val xml = existingXml ?: newXmpDocument()
        val fallbackDescription = allDescriptions(xml).firstOrNull()
            ?: firstDescription(xml)
            ?: return

        values.forEach { (qname, value) ->
            val blocks = allDescriptions(xml)
            // Write back into the block that already owns the property so its
            // prefix keeps the namespace declared on that block. XMP commonly
            // spreads properties across several rdf:Description blocks, one
            // per namespace.
            val target = blocks.firstOrNull { hasXmpProperty(it, qname) }
                ?: fallbackDescription
            blocks.forEach { block -> removeXmpProperty(block, qname) }
            if (!value.isNullOrEmpty()) applyXmpProperty(xml, target, qname, value)
        }

        if (!allDescriptions(xml).any { hasProperties(it) }) {
            root.delete("Metadata")
            return
        }

        val bytesOut = serializeXml(xml).toByteArray(Charsets.UTF_8)
        val dictionary = pdf.newDictionary()
        dictionary.put("Type", pdf.newName("Metadata"))
        dictionary.put("Subtype", pdf.newName("XML"))
        val stream = pdf.addStream(Buffer().apply { writeBytes(bytesOut) }, dictionary)
        root.put("Metadata", stream)
    }

    private fun applyXmpProperty(
        xml: XmlDocument,
        description: Element,
        qname: String,
        value: String,
    ) {
        when (qname) {
            "dc:title", "dc:description" -> setAlt(xml, description, qname, value)
            "dc:creator" -> setSeq(xml, description, qname, value)
            else -> setSimple(xml, description, qname, value)
        }
    }

    private fun setSimple(xml: XmlDocument, description: Element, qname: String, value: String) {
        val element = createElement(xml, description, qname)
        element.textContent = value
        description.appendChild(element)
    }

    private fun setAlt(xml: XmlDocument, description: Element, qname: String, value: String) {
        val element = createElement(xml, description, qname)
        val alt = xml.createElementNS(RDF_NS, "rdf:Alt")
        val li = xml.createElementNS(RDF_NS, "rdf:li")
        li.setAttributeNS(XML_NS, "xml:lang", "x-default")
        li.textContent = value
        alt.appendChild(li)
        element.appendChild(alt)
        description.appendChild(element)
    }

    private fun setSeq(xml: XmlDocument, description: Element, qname: String, value: String) {
        val element = createElement(xml, description, qname)
        val seq = xml.createElementNS(RDF_NS, "rdf:Seq")
        // A single editor field maps to one rdf:li. Semicolons are not split,
        // so an author name containing one is preserved.
        val li = xml.createElementNS(RDF_NS, "rdf:li")
        li.textContent = value
        seq.appendChild(li)
        element.appendChild(seq)
        description.appendChild(element)
    }

    private fun createElement(xml: XmlDocument, description: Element, qname: String): Element {
        val prefix = qname.substringBefore(':', "")
        val local = qname.substringAfter(':', qname)
        val namespace = if (prefix.isEmpty()) {
            null
        } else {
            namespaceFor(description, prefix)
        }
        return if (namespace != null) {
            xml.createElementNS(namespace, "$prefix:$local")
        } else {
            xml.createElement(qname)
        }
    }

    private fun namespaceFor(description: Element, prefix: String): String? {
        description.lookupNamespaceURI(prefix)?.let { return it }
        namespaces[prefix]?.let { return it }
        // A prefix may be declared on a sibling rdf:Description block rather
        // than on this one. Search the document before giving up, so editing a
        // discovered property keeps its namespace intact.
        val elements = description.ownerDocument.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as? Element ?: continue
            val attributes = element.attributes
            for (j in 0 until attributes.length) {
                val attribute = attributes.item(j) as? Attr ?: continue
                if (attribute.prefix == "xmlns" && attribute.localName == prefix) {
                    return attribute.value
                }
            }
        }
        return null
    }

    private fun hasXmpProperty(description: Element, qname: String): Boolean {
        val prefix = qname.substringBefore(':', "")
        val local = qname.substringAfter(':', qname)
        val namespace = namespaceFor(description, prefix)

        val attributes = description.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i) as? Attr ?: continue
            if (attribute.nodeName == qname ||
                (attribute.localName == local &&
                    (namespace == null || attribute.namespaceURI == namespace))
            ) {
                return true
            }
        }

        val children = description.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            if (element.nodeName == qname ||
                (element.localName == local &&
                    (namespace == null || element.namespaceURI == namespace))
            ) {
                return true
            }
        }
        return false
    }

    private fun removeXmpProperty(description: Element, qname: String) {
        val prefix = qname.substringBefore(':', "")
        val local = qname.substringAfter(':', qname)
        val namespace = namespaceFor(description, prefix)

        val attributes = description.attributes
        for (i in attributes.length - 1 downTo 0) {
            val attribute = attributes.item(i) as? Attr ?: continue
            if (attribute.nodeName == qname ||
                (attribute.localName == local &&
                    (namespace == null || attribute.namespaceURI == namespace))
            ) {
                description.removeAttributeNode(attribute)
            }
        }

        val children = description.childNodes
        for (i in children.length - 1 downTo 0) {
            val element = children.item(i) as? Element ?: continue
            if (element.nodeName == qname ||
                (element.localName == local &&
                    (namespace == null || element.namespaceURI == namespace))
            ) {
                description.removeChild(element)
            }
        }
    }

    private fun hasProperties(description: Element): Boolean {
        val attributes = description.attributes
        for (i in 0 until attributes.length) {
            val prefix = attributes.item(i).prefix ?: continue
            if (prefix != "xmlns" && prefix != "xml" && prefix != "rdf") return true
        }
        return description.childNodes.let { nodes ->
            (0 until nodes.length).any { nodes.item(it) is Element }
        }
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

    private fun parseXml(text: String): XmlDocument {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return factory.newDocumentBuilder()
            .parse(text.toByteArray(Charsets.UTF_8).inputStream())
    }

    private fun allDescriptions(xml: XmlDocument): List<Element> {
        val descriptions = xml.getElementsByTagNameNS(RDF_NS, "Description")
        return (0 until descriptions.length).mapNotNull { descriptions.item(it) as? Element }
    }

    private fun firstDescription(xml: XmlDocument): Element? {
        allDescriptions(xml).firstOrNull()?.let { return it }

        val rdf = xml.getElementsByTagNameNS(RDF_NS, "RDF").item(0) as? Element
            ?: return null
        val description = xml.createElementNS(RDF_NS, "rdf:Description")
        description.setAttributeNS(RDF_NS, "rdf:about", "")
        rdf.appendChild(description)
        return description
    }

    private fun newXmpDocument(): XmlDocument {
        val xml = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .newDocument()
        val meta = xml.createElementNS(ADOBE_NS, "x:xmpmeta")
        xml.appendChild(meta)
        val rdf = xml.createElementNS(RDF_NS, "rdf:RDF")
        meta.appendChild(rdf)
        val description = xml.createElementNS(RDF_NS, "rdf:Description")
        description.setAttributeNS(RDF_NS, "rdf:about", "")
        // Declare the prefixes this writer knows about up front. Without this
        // each property would carry its own local declaration, which is valid
        // but unlike the XMP other applications produce.
        namespaces.forEach { (prefix, namespace) ->
            description.setAttributeNS(XMLNS_NS, "xmlns:$prefix", namespace)
        }
        rdf.appendChild(description)
        return xml
    }

    private fun serializeXml(xml: XmlDocument): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        val writer = StringWriter()
        transformer.transform(DOMSource(xml), StreamResult(writer))
        return writer.toString()
    }

    private fun elementValue(element: Element): String {
        val items = element.getElementsByTagNameNS(RDF_NS, "li")
        if (items.length > 0) {
            return (0 until items.length)
                .map { items.item(it).textContent?.trim().orEmpty() }
                .filter { it.isNotEmpty() }
                .joinToString("; ")
        }
        return element.textContent?.trim().orEmpty()
    }
}
