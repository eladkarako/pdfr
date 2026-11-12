package com.karako.pdfr.data

import java.util.TimeZone

/**
 * The editable view of [DocumentInfo] used by the info dialog.
 *
 * [fields] is ordered exactly as the dialog shows it: the standard slots for
 * the format, then every additional property found in the file. Empty values
 * are kept, so a missing property still gets an editable slot; applying an
 * empty value removes it where the format supports removal.
 *
 * [fileCreated] and [fileModified] are filesystem timestamps, not document
 * properties. They are editable because Save as and Share use them.
 */
data class DocumentMetadataDraft(
    val fileName: String,
    val extension: String,
    val fileCreated: String? = null,
    val fileModified: String? = null,
    val fields: List<MetadataField> = emptyList(),
) {

    val displayName: String get() = combineName(fileName, extension)

    val fileNameValid: Boolean get() = fileName.trim().isNotEmpty()

    val extensionValid: Boolean get() = extensionPattern.matches(extension.trim())

    val hasValidDates: Boolean
        get() = listOf(fileCreated, fileModified).all { value ->
            value.isNullOrBlank() || DocumentDates.parseIso(value) != null
        } && fields.all { field ->
            field.kind != MetadataValueKind.DATE_TIME ||
                field.value.isBlank() ||
                DocumentDates.parseFlexible(field.value) != null
        }

    val isValid: Boolean
        get() = fileNameValid && extensionValid && hasValidDates

    fun value(key: String): String? =
        fields.firstOrNull { it.key == key }?.value?.takeIf { it.isNotBlank() }

    val title: String? get() = value(MetadataKeys.TITLE)
    val author: String? get() = value(MetadataKeys.AUTHOR)
    val subject: String? get() = value(MetadataKeys.SUBJECT)
    val keywords: String? get() = value(MetadataKeys.KEYWORDS)
    val creator: String? get() = value(MetadataKeys.CREATOR)
    val producer: String? get() = value(MetadataKeys.PRODUCER)
    val created: String? get() = value(MetadataKeys.CREATION_DATE)
    val modified: String? get() = value(MetadataKeys.MODIFIED_DATE)

    fun fileCreatedMillis(timeZone: TimeZone = TimeZone.getDefault()): Long? =
        DocumentDates.parseIso(fileCreated, timeZone)

    fun fileModifiedMillis(timeZone: TimeZone = TimeZone.getDefault()): Long? =
        DocumentDates.parseIso(fileModified, timeZone)

    /**
     * The filesystem timestamps stored in a sidecar. PDF metadata is written
     * into the PDF itself, but birth and modified times still live beside the
     * cache file because they are not document properties.
     */
    fun fileTimestampValues(): Map<String, String> {
        val values = linkedMapOf<String, String>()
        fileCreated?.let { values[KEY_FILE_CREATED] = it }
        fileModified?.let { values[KEY_FILE_MODIFIED] = it }
        return values
    }

    /** True when the document properties differ from what was read. */
    fun documentChanged(info: DocumentInfo): Boolean =
        fields != MetadataSchema.fields(info)

    /**
     * The Info and XMP values to write for a PDF. Standard slots update both
     * dictionaries; `info:` and `xmp:` entries update only their source.
     */
    fun pdfUpdate(timeZone: TimeZone = TimeZone.getDefault()): PdfMetadata.Update {
        val info = linkedMapOf<String, String?>()
        val xmp = linkedMapOf<String, String?>()

        fields.forEach { field ->
            when {
                MetadataKeys.isStandard(field.key) -> {
                    val standard = PdfMetadataInfo.STANDARD
                        .firstOrNull { it.key == field.key }
                        ?: return@forEach
                    val value = field.value.trim().takeIf { it.isNotEmpty() }
                    info[standard.infoKey] = if (standard.isDate) {
                        value?.let { DocumentDates.toPdfDate(it, timeZone) ?: it }
                    } else {
                        value
                    }
                    xmp[standard.xmpKey] = if (standard.isDate) {
                        value?.let { DocumentDates.normaliseFlexible(it, timeZone) ?: it }
                    } else {
                        value
                    }
                }

                field.key.startsWith(MetadataKeys.INFO_PREFIX) -> {
                    val key = field.key.removePrefix(MetadataKeys.INFO_PREFIX)
                    val value = field.value.trim().takeIf { it.isNotEmpty() }
                    info[key] = if (field.kind == MetadataValueKind.DATE_TIME) {
                        value?.let { DocumentDates.toPdfDate(it, timeZone) ?: it }
                    } else {
                        value
                    }
                }

                field.key.startsWith(MetadataKeys.XMP_PREFIX) -> {
                    val key = field.key.removePrefix(MetadataKeys.XMP_PREFIX)
                    val value = field.value.trim().takeIf { it.isNotEmpty() }
                    xmp[key] = if (field.kind == MetadataValueKind.DATE_TIME) {
                        value?.let { DocumentDates.normaliseFlexible(it, timeZone) ?: it }
                    } else {
                        value
                    }
                }
            }
        }
        return PdfMetadata.Update(info, xmp)
    }

    /**
     * Values stored beside a non-PDF document. Standard keys keep the sidecar
     * names used by older versions; every other field is stored under
     * [EXTRA_FIELD_PREFIX] so its source and key survive a round trip.
     */
    fun sidecarValues(timeZone: TimeZone = TimeZone.getDefault()): Map<String, String> {
        val values = linkedMapOf<String, String>()
        values[KEY_DISPLAY_NAME] = displayName
        fileTimestampValues().forEach { (key, value) -> values[key] = value }

        fields.forEach { field ->
            val sidecarKey = standardSidecarKey(field.key)
            if (sidecarKey != null) {
                values[sidecarKey] = when (field.kind) {
                    MetadataValueKind.DATE_TIME ->
                        DocumentDates.normaliseFlexible(field.value, timeZone) ?: field.value

                    MetadataValueKind.TEXT -> field.value
                }
            } else if (
                field.key.startsWith(MetadataKeys.INFO_PREFIX) ||
                field.key.startsWith(MetadataKeys.XMP_PREFIX) ||
                field.key.startsWith(MetadataKeys.EXIF_PREFIX) ||
                field.key.startsWith(MetadataKeys.PNG_PREFIX) ||
                field.key.startsWith(MetadataKeys.GENERIC_PREFIX)
            ) {
                values[EXTRA_FIELD_PREFIX + field.key] = field.value
            }
        }
        return values
    }

    companion object {

        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_FILE_CREATED = "fileCreated"
        const val KEY_FILE_MODIFIED = "fileModified"
        const val KEY_TITLE = "title"
        const val KEY_AUTHOR = "author"
        const val KEY_SUBJECT = "subject"
        const val KEY_KEYWORDS = "keywords"
        const val KEY_CREATOR = "creator"
        const val KEY_PRODUCER = "producer"
        const val KEY_CREATED = "created"
        const val KEY_MODIFIED = "modified"

        /** Prefix for extra, format-specific fields in the sidecar. */
        const val EXTRA_FIELD_PREFIX = "field:"

        private val extensionPattern = Regex("[A-Za-z0-9]{0,8}")

        fun from(
            info: DocumentInfo,
            lastModified: Long? = null,
            timeZone: TimeZone = TimeZone.getDefault(),
        ): DocumentMetadataDraft {
            val (stem, extension) = splitName(info.fileName)
            val modifiedMillis = info.fileModified ?: lastModified
            val createdMillis = info.fileCreated
            return DocumentMetadataDraft(
                fileName = stem,
                extension = extension,
                fileCreated = createdMillis
                    ?.takeIf { it > 0L }
                    ?.let { DocumentDates.formatIso(it, timeZone) },
                fileModified = modifiedMillis
                    ?.takeIf { it > 0L }
                    ?.let { DocumentDates.formatIso(it, timeZone) },
                fields = MetadataSchema.fields(info),
            )
        }

        fun splitName(displayName: String): Pair<String, String> {
            val name = displayName.trim()
            val dot = name.lastIndexOf('.')
            return if (dot > 0 && dot < name.length - 1) {
                name.substring(0, dot) to name.substring(dot + 1)
            } else {
                name to ""
            }
        }

        fun combineName(stem: String, extension: String): String {
            val base = stem.trim()
            val suffix = extension.trim().trimStart('.')
            return if (suffix.isEmpty()) base else "$base.$suffix"
        }

        /** Applies the sidecar's values on top of what MuPDF read from the file. */
        fun applySidecar(
            info: DocumentInfo,
            values: Map<String, String>?,
            timeZone: TimeZone = TimeZone.getDefault(),
        ): DocumentInfo {
            if (values == null) return info

            fun text(key: String, fallback: String?): String? =
                if (values.containsKey(key)) {
                    values[key]?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    fallback
                }

            fun date(key: String, fallback: String?): String? =
                if (values.containsKey(key)) {
                    DocumentDates.normaliseFlexible(values[key], timeZone)
                } else {
                    fallback
                }

            val modifiedMillis = if (values.containsKey(KEY_FILE_MODIFIED)) {
                DocumentDates.parseIso(values[KEY_FILE_MODIFIED], timeZone) ?: info.fileModified
            } else {
                info.fileModified
            }

            val createdMillis = if (values.containsKey(KEY_FILE_CREATED)) {
                DocumentDates.parseIso(values[KEY_FILE_CREATED], timeZone) ?: info.fileCreated
            } else {
                info.fileCreated
            }

            val overlaid = info.copy(
                fileName = text(KEY_DISPLAY_NAME, info.fileName) ?: info.fileName,
                fileCreated = createdMillis,
                fileModified = modifiedMillis,
                title = text(KEY_TITLE, info.title),
                author = text(KEY_AUTHOR, info.author),
                subject = text(KEY_SUBJECT, info.subject),
                keywords = text(KEY_KEYWORDS, info.keywords),
                creator = text(KEY_CREATOR, info.creator),
                producer = text(KEY_PRODUCER, info.producer),
                created = date(KEY_CREATED, info.created),
                modified = date(KEY_MODIFIED, info.modified),
            )

            // PDF properties are edited in the PDF itself, never in a sidecar.
            if (overlaid.isPdf) return overlaid

            val extras = overlaid.extraFields.toMutableList()
            values.forEach { (key, value) ->
                if (!key.startsWith(EXTRA_FIELD_PREFIX)) return@forEach
                val fieldKey = key.removePrefix(EXTRA_FIELD_PREFIX)
                val index = extras.indexOfFirst { it.key == fieldKey }
                val existingKind = if (index >= 0) extras[index].kind else null
                val kind = existingKind ?: MetadataSchema.kindFor(fieldKey, value, timeZone)
                val field = MetadataField(fieldKey, value, kind)
                if (index >= 0) extras[index] = field else extras += field
            }
            return overlaid.copy(extraFields = extras)
        }

        private fun standardSidecarKey(key: String): String? = when (key) {
            MetadataKeys.TITLE -> KEY_TITLE
            MetadataKeys.AUTHOR -> KEY_AUTHOR
            MetadataKeys.SUBJECT -> KEY_SUBJECT
            MetadataKeys.KEYWORDS -> KEY_KEYWORDS
            MetadataKeys.CREATOR -> KEY_CREATOR
            MetadataKeys.PRODUCER -> KEY_PRODUCER
            MetadataKeys.CREATION_DATE -> KEY_CREATED
            MetadataKeys.MODIFIED_DATE -> KEY_MODIFIED
            else -> null
        }
    }
}
