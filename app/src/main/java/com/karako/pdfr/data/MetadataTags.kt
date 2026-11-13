package com.karako.pdfr.data

/**
 * Literal names used inside PDF and XMP files.
 *
 * These strings are on-disk identifiers, not labels. They must never be
 * translated or renamed: changing one silently stops matching real files. The
 * editor's user-visible labels live in `strings.xml`; this file only answers
 * "which property in the file do we read or write?".
 *
 * Keeping them together means a typo is fixed once, and the PDF writer, the
 * image reader and the XMP helper all agree on the same spelling.
 */

/** Keys of the PDF trailer's `/Info` dictionary. */
object PdfInfoKeys {
    const val TITLE = "Title"
    const val AUTHOR = "Author"
    const val SUBJECT = "Subject"
    const val KEYWORDS = "Keywords"
    const val CREATOR = "Creator"
    const val PRODUCER = "Producer"
    const val CREATION_DATE = "CreationDate"
    const val MODIFIED_DATE = "ModDate"
}

/** Keys and values of the PDF trailer, catalogue and metadata stream. */
object PdfDictionaryKeys {
    const val INFO = "Info"
    const val ROOT = "Root"
    const val METADATA = "Metadata"
    const val TYPE = "Type"
    const val SUBTYPE = "Subtype"
    const val XML = "XML"
}

/**
 * Qualified XMP property names.
 *
 * The prefix is part of the name because XMP addresses properties by
 * namespace-qualified name. The matching namespace URIs are in
 * [XmpNamespaces].
 */
object XmpProperties {
    // Dublin Core: the descriptive properties shared by PDF and images.
    const val DC_TITLE = "dc:title"
    const val DC_CREATOR = "dc:creator"
    const val DC_DESCRIPTION = "dc:description"
    const val DC_SUBJECT = "dc:subject"
    const val DC_RIGHTS = "dc:rights"

    // Adobe PDF namespace.
    const val PDF_KEYWORDS = "pdf:Keywords"
    const val PDF_PRODUCER = "pdf:Producer"

    // Adobe XMP namespace.
    const val XMP_CREATOR_TOOL = "xmp:CreatorTool"
    const val XMP_CREATE_DATE = "xmp:CreateDate"
    const val XMP_MODIFY_DATE = "xmp:ModifyDate"
    const val XMP_RATING = "xmp:Rating"

    // TIFF properties mirrored into XMP by cameras and editors.
    const val TIFF_MAKE = "tiff:Make"
    const val TIFF_MODEL = "tiff:Model"
    const val TIFF_ORIENTATION = "tiff:Orientation"
    const val TIFF_IMAGE_WIDTH = "tiff:ImageWidth"
    const val TIFF_IMAGE_LENGTH = "tiff:ImageLength"
    const val TIFF_X_RESOLUTION = "tiff:XResolution"
    const val TIFF_Y_RESOLUTION = "tiff:YResolution"

    // EXIF properties.
    const val EXIF_DATETIME_ORIGINAL = "exif:DateTimeOriginal"
    const val EXIF_EXPOSURE_TIME = "exif:ExposureTime"
    const val EXIF_F_NUMBER = "exif:FNumber"
    const val EXIF_ISO_SPEED_RATINGS = "exif:ISOSpeedRatings"
    const val EXIF_FOCAL_LENGTH = "exif:FocalLength"
    const val EXIF_COLOR_SPACE = "exif:ColorSpace"
    const val EXIF_EX_LENS_MODEL = "exifEX:LensModel"

    // Photoshop descriptive properties.
    const val PHOTOSHOP_HEADLINE = "photoshop:Headline"
    const val PHOTOSHOP_CITY = "photoshop:City"
    const val PHOTOSHOP_COUNTRY = "photoshop:Country"
}

/**
 * XMP namespace URIs and the prefixes PDFr writes.
 *
 * [PREFIXES] is used when creating a fresh packet so every property can use a
 * short prefix without declaring the namespace on each element.
 */
object XmpNamespaces {
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val XML = "http://www.w3.org/XML/1998/namespace"
    const val XMLNS = "http://www.w3.org/2000/xmlns/"
    const val ADOBE = "adobe:ns:meta/"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val XMP = "http://ns.adobe.com/xap/1.0/"
    const val PDF = "http://ns.adobe.com/pdf/1.3/"
    const val PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/"
    const val XMP_MM = "http://ns.adobe.com/xap/1.0/mm/"
    const val XMP_RIGHTS = "http://ns.adobe.com/xap/1.0/rights/"
    const val TIFF = "http://ns.adobe.com/tiff/1.0/"
    const val EXIF = "http://ns.adobe.com/exif/1.0/"
    const val AUX = "http://ns.adobe.com/exif/1.0/aux/"
    const val EXIF_EX = "http://cipa.jp/exif/1.0/"
    const val IPTC = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"

    /** Prefix-to-URI table in the order PDFr declares them. */
    val PREFIXES: Map<String, String> = linkedMapOf(
        "dc" to DC,
        "xmp" to XMP,
        "pdf" to PDF,
        "photoshop" to PHOTOSHOP,
        "xmpMM" to XMP_MM,
        "xmpRights" to XMP_RIGHTS,
        "tiff" to TIFF,
        "exif" to EXIF,
        "aux" to AUX,
        "exifEX" to EXIF_EX,
        "Iptc4xmpCore" to IPTC,
    )
}
