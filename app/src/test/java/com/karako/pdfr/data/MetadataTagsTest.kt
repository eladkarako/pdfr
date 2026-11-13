package com.karako.pdfr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the on-disk PDF/XMP identifiers. A typo here silently stops matching
 * real files, so the literal values are asserted rather than only their shape.
 */
class MetadataTagsTest {

    @Test
    fun metadataKeysReuseThePdfInfoNames() {
        assertEquals(PdfInfoKeys.TITLE, MetadataKeys.TITLE)
        assertEquals(PdfInfoKeys.AUTHOR, MetadataKeys.AUTHOR)
        assertEquals(PdfInfoKeys.SUBJECT, MetadataKeys.SUBJECT)
        assertEquals(PdfInfoKeys.KEYWORDS, MetadataKeys.KEYWORDS)
        assertEquals(PdfInfoKeys.CREATOR, MetadataKeys.CREATOR)
        assertEquals(PdfInfoKeys.PRODUCER, MetadataKeys.PRODUCER)
        assertEquals(PdfInfoKeys.CREATION_DATE, MetadataKeys.CREATION_DATE)
        assertEquals(PdfInfoKeys.MODIFIED_DATE, MetadataKeys.MODIFIED_DATE)
        assertEquals(MetadataKeys.STANDARD_PDF.size, MetadataKeys.STANDARD_PDF.toSet().size)
    }

    @Test
    fun standardPdfFieldsPointAtTheRightPdfAndXmpSlots() {
        val fields = PdfMetadataInfo.STANDARD.associateBy { it.key }

        assertEquals(PdfInfoKeys.TITLE, fields.getValue(MetadataKeys.TITLE).infoKey)
        assertEquals(XmpProperties.DC_TITLE, fields.getValue(MetadataKeys.TITLE).xmpKey)
        assertEquals(PdfInfoKeys.AUTHOR, fields.getValue(MetadataKeys.AUTHOR).infoKey)
        assertEquals(XmpProperties.DC_CREATOR, fields.getValue(MetadataKeys.AUTHOR).xmpKey)
        assertEquals(
            XmpProperties.XMP_CREATE_DATE,
            fields.getValue(MetadataKeys.CREATION_DATE).xmpKey,
        )
        assertTrue(fields.getValue(MetadataKeys.CREATION_DATE).isDate)
        assertTrue(fields.getValue(MetadataKeys.MODIFIED_DATE).isDate)
    }

    @Test
    fun everyXmpPropertyUsesADeclaredPrefix() {
        val properties = listOf(
            XmpProperties.DC_TITLE,
            XmpProperties.DC_CREATOR,
            XmpProperties.DC_DESCRIPTION,
            XmpProperties.DC_SUBJECT,
            XmpProperties.DC_RIGHTS,
            XmpProperties.PDF_KEYWORDS,
            XmpProperties.PDF_PRODUCER,
            XmpProperties.XMP_CREATOR_TOOL,
            XmpProperties.XMP_CREATE_DATE,
            XmpProperties.XMP_MODIFY_DATE,
            XmpProperties.XMP_RATING,
            XmpProperties.TIFF_MAKE,
            XmpProperties.TIFF_MODEL,
            XmpProperties.TIFF_ORIENTATION,
            XmpProperties.TIFF_IMAGE_WIDTH,
            XmpProperties.TIFF_IMAGE_LENGTH,
            XmpProperties.TIFF_X_RESOLUTION,
            XmpProperties.TIFF_Y_RESOLUTION,
            XmpProperties.EXIF_DATETIME_ORIGINAL,
            XmpProperties.EXIF_EXPOSURE_TIME,
            XmpProperties.EXIF_F_NUMBER,
            XmpProperties.EXIF_ISO_SPEED_RATINGS,
            XmpProperties.EXIF_FOCAL_LENGTH,
            XmpProperties.EXIF_COLOR_SPACE,
            XmpProperties.EXIF_EX_LENS_MODEL,
            XmpProperties.PHOTOSHOP_HEADLINE,
            XmpProperties.PHOTOSHOP_CITY,
            XmpProperties.PHOTOSHOP_COUNTRY,
        )

        properties.forEach { property ->
            val prefix = property.substringBefore(':')
            assertTrue(
                "$property uses an undeclared prefix '$prefix'",
                XmpNamespaces.PREFIXES.containsKey(prefix),
            )
        }
    }

    @Test
    fun namespaceUrisAreUniqueAndStable() {
        val uris = XmpNamespaces.PREFIXES.values.toList()

        assertEquals(uris.size, uris.toSet().size)
        assertEquals("http://purl.org/dc/elements/1.1/", XmpNamespaces.DC)
        assertEquals("http://ns.adobe.com/xap/1.0/", XmpNamespaces.XMP)
        assertEquals("http://ns.adobe.com/pdf/1.3/", XmpNamespaces.PDF)
        assertEquals(
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            XmpNamespaces.RDF,
        )
    }

    @Test
    fun pdfDictionaryKeysKeepTheirOnDiskSpelling() {
        assertEquals("Info", PdfDictionaryKeys.INFO)
        assertEquals("Root", PdfDictionaryKeys.ROOT)
        assertEquals("Metadata", PdfDictionaryKeys.METADATA)
        assertEquals("Type", PdfDictionaryKeys.TYPE)
        assertEquals("Subtype", PdfDictionaryKeys.SUBTYPE)
        assertEquals("XML", PdfDictionaryKeys.XML)
    }
}
