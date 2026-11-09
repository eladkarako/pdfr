package com.karako.pdfr.data

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Converts the dates found in document metadata and the filesystem to the
 * single format the info editor speaks: `yyyy-MM-ddThh:mm:ss`, without
 * milliseconds or a timezone.
 *
 * PDF stores dates as `D:YYYYMMDDHHmmSS` followed by an optional `Z` or
 * `+HH'mm'` offset. MuPDF returns that string verbatim for the creation and
 * modification fields, while the filesystem gives milliseconds. Both are
 * normalised here so the dialog and the sidecar never have to deal with the
 * PDF syntax.
 *
 * A [TimeZone] parameter is accepted for deterministic unit tests; the app
 * always uses the device's current timezone. Values without an offset are
 * interpreted in that timezone; values with an offset are converted to it.
 */
object DocumentDates {

    const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"

    private const val PDF_READ_PATTERN = "yyyyMMddHHmmss"
    private const val PDF_WRITE_PATTERN = "'D:'yyyyMMddHHmmss"

    /**
     * Matches `D:YYYYMMDDHHmmSS`, any missing trailing components, and an
     * optional `Z` / `+HH'mm'` / `-HH'mm'` offset. Apostrophes around the
     * offset minutes are optional because real files contain both spellings.
     */
    private val pdfDate = Regex(
        "^D:(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?" +
            "(?:([+\\-Z])(\\d{2})?'?(\\d{2})?'?)?$",
        RegexOption.IGNORE_CASE,
    )

    fun formatIso(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat(ISO_PATTERN, Locale.US)
        format.timeZone = timeZone
        return format.format(Date(millis))
    }

    /** Parses the exact ISO shape used by the editor. Empty or invalid values are null. */
    fun parseIso(value: String?, timeZone: TimeZone = TimeZone.getDefault()): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return parseExact(ISO_PATTERN, text, timeZone)
    }

    /**
     * Accepts a PDF date or an ISO date and returns the ISO form. Values that
     * cannot be parsed are returned trimmed rather than discarded, so metadata
     * written by an unusual producer is still visible and editable.
     */
    fun normalise(value: String?, timeZone: TimeZone = TimeZone.getDefault()): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val millis = if (text.startsWith("D:", ignoreCase = true)) {
            parsePdfDate(text, timeZone)
        } else {
            parseIso(text, timeZone)
        }
        return millis?.let { formatIso(it, timeZone) } ?: text
    }

    /** Converts an ISO value to PDF's `D:YYYYMMDDHHmmSS` syntax. */
    fun toPdfDate(value: String?, timeZone: TimeZone = TimeZone.getDefault()): String? {
        val text = value?.trim() ?: return null
        if (text.isEmpty()) return ""
        val millis = if (text.startsWith("D:", ignoreCase = true)) {
            parsePdfDate(text, timeZone)
        } else {
            parseIso(text, timeZone)
        }
        if (millis == null) return text
        val format = SimpleDateFormat(PDF_WRITE_PATTERN, Locale.US)
        format.timeZone = timeZone
        return format.format(Date(millis))
    }

    fun parsePdfDate(value: String, timeZone: TimeZone = TimeZone.getDefault()): Long? {
        val match = pdfDate.matchEntire(value.trim()) ?: return null
        val groups = match.groupValues
        val year = groups[1].toIntOrNull() ?: return null
        val month = groups[2].toIntOrNull()?.takeIf { it in 1..12 } ?: 1
        val day = groups[3].toIntOrNull()?.takeIf { it in 1..31 } ?: 1
        val hour = groups[4].toIntOrNull()?.takeIf { it in 0..23 } ?: 0
        val minute = groups[5].toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        val second = groups[6].toIntOrNull()?.takeIf { it in 0..59 } ?: 0

        val sign = groups[7]
        val offsetHour = groups[8].toIntOrNull()
        val offsetMinute = groups[9].toIntOrNull() ?: 0
        val formatZone = when {
            sign == "+" || sign == "-" -> {
                val hours = (offsetHour ?: 0).coerceIn(0, 23)
                val minutes = offsetMinute.coerceIn(0, 59)
                TimeZone.getTimeZone(
                    String.format(Locale.US, "GMT%s%02d:%02d", sign, hours, minutes),
                )
            }
            sign.equals("Z", ignoreCase = true) -> TimeZone.getTimeZone("UTC")
            else -> timeZone
        }

        val format = SimpleDateFormat(PDF_READ_PATTERN, Locale.US)
        format.timeZone = formatZone
        format.isLenient = false
        val text = String.format(
            Locale.US,
            "%04d%02d%02d%02d%02d%02d",
            year,
            month,
            day,
            hour,
            minute,
            second,
        )
        return format.parse(text, ParsePosition(0))?.time
    }

    private fun parseExact(pattern: String, value: String, timeZone: TimeZone): Long? {
        val format = SimpleDateFormat(pattern, Locale.US)
        format.timeZone = timeZone
        format.isLenient = false
        val position = ParsePosition(0)
        val parsed = format.parse(value, position) ?: return null
        // SimpleDateFormat happily stops after a valid prefix. The editor
        // should reject trailing garbage instead of silently accepting it.
        if (position.index != value.length) return null
        return parsed.time
    }
}
