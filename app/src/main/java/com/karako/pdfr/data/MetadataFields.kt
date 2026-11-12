package com.karako.pdfr.data

/** How the info dialog edits a metadata value. */
enum class MetadataValueKind { TEXT, DATE_TIME }

/**
 * One editable metadata slot.
 *
 * [key] is a stable identifier, never the label shown to the reader. Standard
 * PDF slots use names such as `Title`; values discovered in the file keep a
 * source prefix (`info:`, `xmp:`, `exif:` or `png:`), and values stored only in
 * the sidecar use `meta:`. The value is always a string: an empty value means
 * "remove this property" when the format supports removal.
 */
data class MetadataField(
    val key: String,
    val value: String = "",
    val kind: MetadataValueKind = MetadataValueKind.TEXT,
)

/** Stable keys shared by the draft, the PDF writer and the sidecar. */
object MetadataKeys {
    const val TITLE = "Title"
    const val AUTHOR = "Author"
    const val SUBJECT = "Subject"
    const val KEYWORDS = "Keywords"
    const val CREATOR = "Creator"
    const val PRODUCER = "Producer"
    const val CREATION_DATE = "CreationDate"
    const val MODIFIED_DATE = "ModDate"

    const val INFO_PREFIX = "info:"
    const val XMP_PREFIX = "xmp:"
    const val EXIF_PREFIX = "exif:"
    const val PNG_PREFIX = "png:"
    const val GENERIC_PREFIX = "meta:"

    val STANDARD_PDF = listOf(
        TITLE,
        AUTHOR,
        SUBJECT,
        KEYWORDS,
        CREATOR,
        PRODUCER,
        CREATION_DATE,
        MODIFIED_DATE,
    )

    fun isStandard(key: String): Boolean = key in STANDARD_PDF
}

/**
 * The ordered slots shown in the info dialog.
 *
 * PDFs always get the eight standard slots, even when the file has none, and
 * then every additional Info or XMP property that exists. Other formats get
 * the same standard slots plus anything their reader discovered. Dates are
 * classified as [MetadataValueKind.DATE_TIME] so the dialog offers pickers.
 */
object MetadataSchema {

    private val dateName = Regex("(date|time)", RegexOption.IGNORE_CASE)

    fun fields(info: DocumentInfo): List<MetadataField> {
        val fields = mutableListOf<MetadataField>()

        if (info.isPdf) {
            pdfFields(info).forEach(fields::add)
        } else {
            standardFields(info).forEach(fields::add)
            info.extraFields.forEach { field ->
                if (fields.none { it.key == field.key }) fields += field
            }
        }
        return fields
    }

    fun looksLikeDate(key: String): Boolean = dateName.containsMatchIn(key)

    fun kindFor(key: String, value: String, timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): MetadataValueKind {
        if (!looksLikeDate(key)) return MetadataValueKind.TEXT
        // A date-like key with text that is not a date probably came from an
        // unusual producer; keep it as text so it stays editable instead of
        // blocking Apply.
        return if (value.isBlank() || DocumentDates.parseFlexible(value, timeZone) != null) {
            MetadataValueKind.DATE_TIME
        } else {
            MetadataValueKind.TEXT
        }
    }

    private fun standardFields(info: DocumentInfo): List<MetadataField> = listOf(
        MetadataField(MetadataKeys.TITLE, info.title.orEmpty()),
        MetadataField(MetadataKeys.AUTHOR, info.author.orEmpty()),
        MetadataField(MetadataKeys.SUBJECT, info.subject.orEmpty()),
        MetadataField(MetadataKeys.KEYWORDS, info.keywords.orEmpty()),
        MetadataField(MetadataKeys.CREATOR, info.creator.orEmpty()),
        MetadataField(MetadataKeys.PRODUCER, info.producer.orEmpty()),
        MetadataField(
            MetadataKeys.CREATION_DATE,
            info.created.orEmpty(),
            MetadataValueKind.DATE_TIME,
        ),
        MetadataField(
            MetadataKeys.MODIFIED_DATE,
            info.modified.orEmpty(),
            MetadataValueKind.DATE_TIME,
        ),
    )

    private fun pdfFields(info: DocumentInfo): List<MetadataField> {
        val fields = mutableListOf<MetadataField>()
        val consumedInfo = mutableSetOf<String>()
        val consumedXmp = mutableSetOf<String>()

        for (standard in PdfMetadataInfo.STANDARD) {
            consumedInfo += standard.infoKey
            consumedXmp += standard.xmpKey
            val value = info.pdfInfo[standard.infoKey]
                ?: info.pdfXmp[standard.xmpKey]
                ?: fallbackStandard(info, standard.key)
                ?: ""
            fields += MetadataField(
                key = standard.key,
                value = value,
                kind = if (standard.isDate) MetadataValueKind.DATE_TIME else MetadataValueKind.TEXT,
            )
        }

        info.pdfInfo.forEach { (name, value) ->
            if (name !in consumedInfo) {
                val kind = kindFor(name, value)
                fields += MetadataField(
                    key = MetadataKeys.INFO_PREFIX + name,
                    value = if (kind == MetadataValueKind.DATE_TIME) {
                        DocumentDates.normaliseFlexible(value) ?: value
                    } else {
                        value
                    },
                    kind = kind,
                )
            }
        }
        info.pdfXmp.forEach { (name, value) ->
            if (name !in consumedXmp) {
                val kind = kindFor(name, value)
                fields += MetadataField(
                    key = MetadataKeys.XMP_PREFIX + name,
                    value = if (kind == MetadataValueKind.DATE_TIME) {
                        DocumentDates.normaliseFlexible(value) ?: value
                    } else {
                        value
                    },
                    kind = kind,
                )
            }
        }
        return fields
    }

    private fun fallbackStandard(info: DocumentInfo, key: String): String? = when (key) {
        MetadataKeys.TITLE -> info.title
        MetadataKeys.AUTHOR -> info.author
        MetadataKeys.SUBJECT -> info.subject
        MetadataKeys.KEYWORDS -> info.keywords
        MetadataKeys.CREATOR -> info.creator
        MetadataKeys.PRODUCER -> info.producer
        MetadataKeys.CREATION_DATE -> info.created
        MetadataKeys.MODIFIED_DATE -> info.modified
        else -> null
    }
}
