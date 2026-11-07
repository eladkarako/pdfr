package com.karako.pdfr.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A reader only needs one document at a time, so incoming files are copied
 * into a single cache directory. The copy is what makes the Share button
 * reliable: the original content:// URI may be readable only by us, while the
 * cached file can be handed to any app through FileProvider.
 */
object DocumentStore {

    private const val DIR = "documents"
    private const val MAX_NAME_BYTES = 160

    // Only characters that are illegal on Android's filesystems (or control
    // characters) are replaced. Everything else - Hebrew, Arabic, CJK, accents,
    // emoji - survives, so the cached file still has a readable name.
    private val illegalChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun displayName(context: Context, uri: Uri): String? {
        val queried = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (queried != null) return queried

        // A few providers do not return DISPLAY_NAME. Their last path segment
        // is only useful when it looks like a real file name, not an opaque
        // document id such as "msf:1234".
        // Uri.lastPathSegment is already percent-decoded.
        return runCatching { uri.lastPathSegment }.getOrNull()
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { it.length <= 255 && DocumentFormats.extensionOf(it) != null }
    }

    /**
     * Reads the source file's last-modified time, in milliseconds. Providers
     * are inconsistent: DocumentsProvider reports milliseconds while
     * MediaStore reports seconds, so the value is normalised.
     */
    fun sourceLastModified(context: Context, uri: Uri): Long? {
        val fromDocuments = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()

        val fromMediaStore = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()

        return normaliseTimestamp(fromDocuments) ?: normaliseTimestamp(fromMediaStore)
    }

    /**
     * Best-effort preservation of a file's last-modified time on the target of
     * a "Save as" operation. SAF providers are not required to accept either
     * column, so unsupported updates are ignored.
     */
    fun applyLastModified(context: Context, uri: Uri, millis: Long?) {
        val value = millis?.takeIf { it > 0 } ?: return

        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, value)
                },
                null,
                null,
            )
        }
        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_MODIFIED, value / 1000L)
                },
                null,
                null,
            )
        }
    }

    private fun normaliseTimestamp(value: Long?): Long? {
        if (value == null || value <= 0L) return null
        // Epoch milliseconds are ~1.7e12 today; seconds are ~1.7e9.
        return if (value < 100_000_000_000L) value * 1000L else value
    }

    suspend fun import(
        context: Context,
        uri: Uri,
        displayName: String?,
        lastModified: Long? = null,
    ): File =
        withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, DIR).apply { mkdirs() }

            // Every import gets its own directory. Re-importing a document with
            // the same display name therefore cannot overwrite - or truncate -
            // the file that is currently on screen.
            val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            val target = File(dir, safeFileName(displayName ?: "document"))
            val staging = File(dir, "import.tmp")

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    staging.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("Couldn't read that file. Try another copy of it.")

                if (staging.length() == 0L) {
                    throw IOException("That file is empty.")
                }

                if (!staging.renameTo(target)) {
                    staging.copyTo(target, overwrite = true)
                    staging.delete()
                }
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }

            // Keep the source timestamp on the cached copy. Save as and the
            // reader's own rewrites can then carry it through unchanged.
            lastModified?.takeIf { it > 0L }?.let { target.setLastModified(it) }

            // The new document is now the only one worth keeping.
            removeStaleFiles(root, keep = target)
            target
        }

    suspend fun cleanup(context: Context, keep: File?) = withContext(Dispatchers.IO) {
        removeStaleFiles(File(context.cacheDir, DIR), keep = keep)
    }

    /**
     * Allocates a cache file for a reordered, rotated, linearized or compacted
     * rewrite. Each rewrite gets its own directory so the visible file name can
     * stay readable without colliding with the document it replaces.
     */
    fun derivedFile(context: Context, displayName: String): File {
        val root = File(context.cacheDir, DIR).apply { mkdirs() }
        val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, safeFileName(displayName))
    }

    /**
     * Deletes a cache document (and its now-empty per-import directory) after
     * the reader has replaced it. The documents root is never removed.
     */
    fun discard(file: File) {
        file.delete()
        val parent = file.parentFile ?: return
        val root = parent.parentFile ?: return
        if (parent != root && parent.list()?.isEmpty() == true) {
            parent.delete()
        }
    }

    private fun removeStaleFiles(root: File, keep: File?) {
        // Older builds stored the document directly in root; newer ones keep it
        // in a per-import directory. Keep whichever entry owns the open file.
        val keepEntry = keep?.let { if (it.parentFile == root) it else it.parentFile }
        root.listFiles()?.forEach { child ->
            if (child != keepEntry) {
                child.deleteRecursively()
            }
        }
    }

    internal fun safeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base
            .replace(illegalChars, "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimStart('.')
            .ifBlank { "document" }

        val extension = DocumentFormats.extensionOf(cleaned)
            ?.lowercase()
            ?.takeIf { it.length <= 8 }

        val stem = if (extension != null) {
            cleaned.dropLast(extension.length + 1).trimEnd()
        } else {
            cleaned
        }.ifBlank { "document" }

        // Cap the stem by UTF-8 byte length (not character count) so a long
        // CJK name still fits Android's per-file name limit.
        val suffix = extension?.let { ".$it" }.orEmpty()
        val maxStemBytes = (MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size)
            .coerceAtLeast(16)
        return truncateUtf8(stem, maxStemBytes) + suffix
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value

        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            out.appendCodePoint(codePoint)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return out.toString().trimEnd().ifBlank { "document" }
    }
}
