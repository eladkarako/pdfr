package com.karako.pdfr.data

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Details from the file itself plus the PDF document information dictionary.
 *
 * [encrypted] describes the document the user opened, not the temporary
 * decrypted copy the viewer is reading.
 */
data class PdfDocumentInfo(
    val fileName: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val encrypted: Boolean,
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String? = null,
    val producer: String? = null,
    val created: Long? = null,
    val modified: Long? = null,
) {
    val hasMetadata: Boolean
        get() = listOf(title, author, subject, keywords, creator, producer, created, modified)
            .any { it != null }
}

data class PreparedPdf(
    val file: File,
    val info: PdfDocumentInfo,
)

/** The document is encrypted and no password was supplied yet. */
class PdfPasswordRequiredException : Exception()

/** A password was supplied, but the document rejected it. */
class PdfWrongPasswordException : Exception()

/**
 * PDFBox is used only to read metadata and to make password-protected files
 * readable by Android's PdfRenderer. Rendering still happens in Pdf-Viewer.
 */
object PdfDocuments {

    // Parsing happens in memory up to this point, then spills into the app's
    // cache directory. That keeps very large PDFs from exhausting the heap.
    private const val MEMORY_BYTES = 16L * 1024 * 1024

    private val memorySetting = MemoryUsageSetting.setupMixed(MEMORY_BYTES)

    /**
     * Loads [file] with [password] (null when the user has not been asked yet).
     *
     * A successful load of an encrypted document produces a decrypted copy that
     * the renderer and the share sheet can read. The encrypted source is
     * removed once that copy is complete.
     */
    fun prepare(file: File, displayName: String, password: String?): PreparedPdf {
        val document = try {
            PDDocument.load(file, password.orEmpty(), memorySetting)
        } catch (e: InvalidPasswordException) {
            throw if (password == null) PdfPasswordRequiredException() else PdfWrongPasswordException()
        }

        // The try block yields the metadata and the decrypted temp file together,
        // so no nullable variable is needed.
        val (info, decrypted) = try {
            val wasEncrypted = document.isEncrypted
            val info = readInfo(document, file, displayName, wasEncrypted)
            if (!wasEncrypted) return PreparedPdf(file, info)

            val temp = File(file.parentFile, "unlocked-${UUID.randomUUID()}.pdf")
            try {
                document.isAllSecurityToBeRemoved = true
                document.save(temp)
            } catch (e: Exception) {
                temp.delete()
                throw e
            }

            if (temp.length() == 0L) {
                temp.delete()
                throw IOException("The decrypted PDF is empty.")
            }

            info to temp
        } finally {
            document.close()
        }

        // Give the readable copy the name the user opened, so sharing does not
        // expose a random "unlocked-<uuid>.pdf". Renaming happens after the
        // document is closed, and only once the source is no longer needed.
        file.delete()
        val readable = if (decrypted.renameTo(file)) file else decrypted
        return PreparedPdf(readable, info)
    }

    /**
     * Reads metadata from a file that the viewer already accepted. Used after
     * process death, when only the file path was saved.
     */
    fun inspect(file: File, displayName: String): PdfDocumentInfo =
        PDDocument.load(file, "", memorySetting).use { document ->
            readInfo(document, file, displayName, document.isEncrypted)
        }

    private fun readInfo(
        document: PDDocument,
        file: File,
        displayName: String,
        encrypted: Boolean,
    ): PdfDocumentInfo {
        val metadata = runCatching { document.documentInformation }.getOrNull()

        fun text(value: () -> String?): String? = runCatching(value)
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        fun date(value: () -> java.util.Calendar?): Long? = runCatching(value)
            .getOrNull()
            ?.timeInMillis

        return PdfDocumentInfo(
            fileName = displayName,
            sizeBytes = file.length(),
            pageCount = runCatching { document.numberOfPages }.getOrDefault(0),
            encrypted = encrypted,
            title = text { metadata?.title },
            author = text { metadata?.author },
            subject = text { metadata?.subject },
            keywords = text { metadata?.keywords },
            creator = text { metadata?.creator },
            producer = text { metadata?.producer },
            created = date { metadata?.creationDate },
            modified = date { metadata?.modificationDate },
        )
    }
}
