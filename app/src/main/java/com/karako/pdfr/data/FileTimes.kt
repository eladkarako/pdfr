package com.karako.pdfr.data

import android.os.Build
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Filesystem timestamps for the info editor.
 *
 * Android only exposes a writable last-modified time through [File]. The
 * creation ("birth") time is read through NIO where the kernel supports it;
 * on older releases, or on filesystems that do not record one, it falls back
 * to the last-modified time so the editor still has a sensible value.
 *
 * Edited values are kept with the document cache (see [MetadataSidecar]) and
 * are what Save as and Share carry, because Java cannot change a file's birth
 * time on Android.
 */
object FileTimes {

    fun created(file: File): Long? {
        val birth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                    .creationTime()
                    .toMillis()
            }.getOrNull()
        } else {
            null
        }
        return birth?.takeIf { it > 0L }
            ?: file.lastModified().takeIf { it > 0L }
    }
}
