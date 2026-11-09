package com.karako.pdfr.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.karako.pdfr.R
import java.io.File

object ShareSheet {

    /**
     * Shares the cached document through FileProvider. ClipData is set as well
     * as the read flag because some OEM choosers only propagate grants through
     * ClipData.
     *
     * When the reader edited the display name (or extension), a copy with that
     * name is shared; the cache file itself keeps the internal suffix MuPDF
     * needs. [lastModified] is carried onto that copy where possible.
     */
    fun shareDocument(
        context: Context,
        file: File,
        mimeType: String,
        displayName: String = file.name,
        lastModified: Long? = null,
    ) {
        val shared = if (displayName != file.name) {
            runCatching {
                DocumentStore.shareCopy(context, file, displayName, lastModified)
            }.getOrDefault(file)
        } else {
            file
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shared,
        )

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, displayName)
            clipData = ClipData.newUri(context.contentResolver, displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(
                send,
                context.getString(R.string.share_document)
            )
        )
    }
}
