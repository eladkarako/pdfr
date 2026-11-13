package com.karako.pdfr.data

import org.w3c.dom.Attr
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document as XmlDocument

/**
 * A small XMP reader and writer shared by PDF and image metadata.
 *
 * Only simple properties are exposed: elements or attributes with a text,
 * `rdf:Alt`, `rdf:Seq` or `rdf:Bag` value. That is enough for the fields an
 * editor can sensibly show, and it keeps unusual structures in the file
 * untouched: properties this class does not understand are left in place.
 *
 * [update] changes the first block that already owns a property, so its prefix
 * keeps the namespace declared on that block. All other copies are removed
 * first, because XMP commonly spreads properties over several
 * `rdf:Description` blocks and a stale duplicate would win on the next read.
 */
object Xmp {

    // Namespace URIs and the prefix table are shared with the metadata
    // writers, so they live in XmpNamespaces.
    private const val RDF_NS = XmpNamespaces.RDF
    private const val XML_NS = XmpNamespaces.XML
    private const val XMLNS_NS = XmpNamespaces.XMLNS
    private const val ADOBE_NS = XmpNamespaces.ADOBE

    private val namespaces = XmpNamespaces.PREFIXES

    /** Reads every simple property, keyed by qualified name such as `dc:title`. */
    fun read(text: String): Map<String, String> {
        val xml = runCatching { parseXml(text) }.getOrNull() ?: return emptyMap()
        val values = linkedMapOf<String, String>()
        allDescriptions(xml).forEach { description ->
            val attributes = description.attributes
            for (i in 0 until attributes.length) {
                val attribute = attributes.item(i) ?: continue
                val prefix = attribute.prefix ?: continue
                if (prefix == "xmlns" || prefix == "xml" || prefix == "rdf") continue
                val qname =
                    "$prefix:${attribute.localName ?: attribute.nodeName.substringAfter(':')}"
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

    /**
     * Applies [values] to [text]. A null or empty value removes the property.
     * Returns null when no properties are left, so the caller can drop the XMP
     * container entirely.
     */
    fun update(text: String?, values: Map<String, String?>): String? {
        if (values.isEmpty()) return text

        val existing = text?.let { runCatching { parseXml(it) }.getOrNull() }
        if (existing == null && values.values.all { it.isNullOrEmpty() }) return text

        val xml = existing ?: newDocument()
        val fallback = allDescriptions(xml).firstOrNull()
            ?: firstDescription(xml)
            ?: return text

        values.forEach { (qname, value) ->
            val blocks = allDescriptions(xml)
            val target = blocks.firstOrNull { hasProperty(it, qname) } ?: fallback
            blocks.forEach { block -> removeProperty(block, qname) }
            if (!value.isNullOrEmpty()) setProperty(xml, target, qname, value)
        }

        if (!allDescriptions(xml).any { hasProperties(it) }) return null
        return serialize(xml)
    }

    /** Parses the values of an XMP stream, or returns null when it is unreadable. */
    fun parse(text: String?): XmlDocument? =
        text?.let { runCatching { parseXml(it) }.getOrNull() }

    private fun setProperty(xml: XmlDocument, description: Element, qname: String, value: String) {
        when (qname) {
            XmpProperties.DC_TITLE,
            XmpProperties.DC_DESCRIPTION,
            XmpProperties.DC_RIGHTS,
                -> setAlt(xml, description, qname, value)

            XmpProperties.DC_CREATOR -> setSeq(xml, description, qname, value)
            XmpProperties.DC_SUBJECT -> setBag(xml, description, qname, value)
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

    private fun setBag(xml: XmlDocument, description: Element, qname: String, value: String) {
        val element = createElement(xml, description, qname)
        val bag = xml.createElementNS(RDF_NS, "rdf:Bag")
        value.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { item ->
                val li = xml.createElementNS(RDF_NS, "rdf:li")
                li.textContent = item
                bag.appendChild(li)
            }
        element.appendChild(bag)
        description.appendChild(element)
    }

    private fun createElement(xml: XmlDocument, description: Element, qname: String): Element {
        val prefix = qname.substringBefore(':', "")
        val local = qname.substringAfter(':', qname)
        val namespace = if (prefix.isEmpty()) null else namespaceFor(description, prefix)
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
        // than on this one. Search the document before giving up.
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

    private fun hasProperty(description: Element, qname: String): Boolean {
        val prefix = qname.substringBefore(':', "")
        val local = qname.substringAfter(':', qname)
        val namespace = namespaceFor(description, prefix)

        val attributes = description.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i) as? Attr ?: continue
            if (matches(
                    attribute.nodeName,
                    attribute.localName,
                    attribute.namespaceURI,
                    qname,
                    local,
                    namespace
                )
            ) {
                return true
            }
        }

        val children = description.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            if (matches(
                    element.nodeName,
                    element.localName,
                    element.namespaceURI,
                    qname,
                    local,
                    namespace
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun matches(
        name: String,
        localName: String?,
        namespaceUri: String?,
        qname: String,
        local: String,
        namespace: String?,
    ): Boolean = name == qname ||
            (localName == local && (namespace == null || namespaceUri == namespace))

    private fun removeProperty(description: Element, qname: String) {
        val prefix = qname.substringBefore(':', "")
        val local = qname.substringAfter(':', qname)
        val namespace = namespaceFor(description, prefix)

        val attributes = description.attributes
        for (i in attributes.length - 1 downTo 0) {
            val attribute = attributes.item(i) as? Attr ?: continue
            if (matches(
                    attribute.nodeName,
                    attribute.localName,
                    attribute.namespaceURI,
                    qname,
                    local,
                    namespace
                )
            ) {
                description.removeAttributeNode(attribute)
            }
        }

        val children = description.childNodes
        for (i in children.length - 1 downTo 0) {
            val element = children.item(i) as? Element ?: continue
            if (matches(
                    element.nodeName,
                    element.localName,
                    element.namespaceURI,
                    qname,
                    local,
                    namespace
                )
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

    private fun newDocument(): XmlDocument {
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

    private fun serialize(xml: XmlDocument): String {
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
