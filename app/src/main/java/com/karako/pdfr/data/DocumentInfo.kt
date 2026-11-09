package com.karako.pdfr.data

/**
 * Details read from the document itself. [encrypted] describes the file on
 * disk; it does not mean the document is still locked.
 */
data class DocumentInfo(
    val fileName: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val kind: String,
    val format: String? = null,
    val encrypted: Boolean = false,
    val encryption: String? = null,
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String? = null,
    val producer: String? = null,
    val created: String? = null,
    val modified: String? = null,
    /**
     * Timestamp of the cached file in milliseconds, initially copied from the
     * original document. Editable in the info dialog and used by Save as and
     * Share.
     */
    val fileModified: Long? = null,
) {
    val hasMetadata: Boolean
        get() = listOf(
            title, author, subject, keywords, creator, producer, created, modified,
        ).any { it != null }

    /** True for PDF documents; the page-rewrite actions are PDF-only. */
    val isPdf: Boolean
        get() = kind.equals("PDF", ignoreCase = true) ||
            format?.trim()?.uppercase()?.startsWith("PDF") == true
}

/** The document is encrypted and no password has been supplied yet. */
class DocumentPasswordRequiredException : Exception()

/** A password was supplied, but the document rejected it. */
class DocumentWrongPasswordException : Exception()
