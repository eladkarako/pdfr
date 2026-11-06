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
     */
    fun shareDocument(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_document)))
    }
}
