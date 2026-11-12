package com.karako.pdfr.data

import com.artifex.mupdf.fitz.Document
import java.util.TimeZone

/**
 * The editable view of [DocumentInfo] used by the info dialog.
 *
 * Only metadata that already exists is carried in a non-null field, so the
 * dialog can render exactly those values and never invent new properties.
 * Editing a value to an empty string means "remove the value"; fields that
 * were absent stay absent.
 *
 * [fileModified] is the cached file's timestamp (seeded from the original
 * file whenever the provider exposed one). It is not a document property, but
 * it is editable here because Save as and Share use it.
 */
data class DocumentMetadataDraft(
    val fileName: String,
    val extension: String,
    val fileCreated: String? = null,
    val fileModified: String? = null,
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String? = null,
    val producer: String? = null,
    val created: String? = null,
    val modified: String? = null,
) {

    val displayName: String get() = combineName(fileName, extension)

    val fileNameValid: Boolean get() = fileName.trim().isNotEmpty()

    val extensionValid: Boolean get() = extensionPattern.matches(extension.trim())

    val hasValidDates: Boolean
        get() = listOf(fileCreated, fileModified, created, modified).all { value ->
            value.isNullOrBlank() || DocumentDates.parseIso(value) != null
        }

    val isValid: Boolean
        get() = fileNameValid && extensionValid && hasValidDates

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

    /** Metadata keys understood by MuPDF's `Document.setMetaData`. */
    fun pdfMetadata(timeZone: TimeZone = TimeZone.getDefault()): Map<String, String> {
        val values = linkedMapOf<String, String>()
        title?.let { values[Document.META_INFO_TITLE] = it }
        author?.let { values[Document.META_INFO_AUTHOR] = it }
        subject?.let { values[Document.META_INFO_SUBJECT] = it }
        keywords?.let { values[Document.META_INFO_KEYWORDS] = it }
        creator?.let { values[Document.META_INFO_CREATOR] = it }
        producer?.let { values[Document.META_INFO_PRODUCER] = it }
        created?.let {
            values[Document.META_INFO_CREATIONDATE] = DocumentDates.toPdfDate(it, timeZone) ?: it
        }
        modified?.let {
            values[Document.META_INFO_MODIFICATIONDATE] =
                DocumentDates.toPdfDate(it, timeZone) ?: it
        }
        return values
    }

    /**
     * Values stored beside a non-PDF document. A key is present for every
     * editable field the document already had; an empty value therefore
     * overrides the file's own metadata with "removed" when it is read back.
     */
    fun sidecarValues(timeZone: TimeZone = TimeZone.getDefault()): Map<String, String> {
        val values = linkedMapOf<String, String>()
        values[KEY_DISPLAY_NAME] = displayName
        fileCreated?.let { values[KEY_FILE_CREATED] = it }
        fileModified?.let { values[KEY_FILE_MODIFIED] = it }
        title?.let { values[KEY_TITLE] = it }
        author?.let { values[KEY_AUTHOR] = it }
        subject?.let { values[KEY_SUBJECT] = it }
        keywords?.let { values[KEY_KEYWORDS] = it }
        creator?.let { values[KEY_CREATOR] = it }
        producer?.let { values[KEY_PRODUCER] = it }
        created?.let {
            values[KEY_CREATED] = DocumentDates.normalise(it, timeZone) ?: it
        }
        modified?.let {
            values[KEY_MODIFIED] = DocumentDates.normalise(it, timeZone) ?: it
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
                title = info.title,
                author = info.author,
                subject = info.subject,
                keywords = info.keywords,
                creator = info.creator,
                producer = info.producer,
                created = info.created,
                modified = info.modified,
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
                    DocumentDates.normalise(values[key], timeZone)
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

            return info.copy(
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
        }
    }
}
