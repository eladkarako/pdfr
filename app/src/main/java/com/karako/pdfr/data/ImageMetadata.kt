package com.karako.pdfr.data

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.TimeZone

/**
 * One slot in the image metadata editor.
 *
 * [exifTag] is read and written through [ExifInterface]; [xmpKey] is the XMP
 * property that mirrors the same value, and also supplies it when the EXIF tag
 * is missing. [iptcDataset] is the read-only IPTC field used as a last
 * fallback. Caption, keywords and the other descriptive slots are XMP-first
 * because that is the modern, writable home for them.
 */
data class ImageFieldSpec(
    val key: String,
    val exifTag: String? = null,
    val xmpKey: String? = null,
    val iptcDataset: Int? = null,
    val fallbackExifTag: String? = null,
    val kind: MetadataValueKind = MetadataValueKind.TEXT,
)

/**
 * Reads and writes still-image metadata.
 *
 * JPEG, PNG and WebP can be written in place through [ExifInterface]; PNG
 * `tEXt`, `iTXt`, `pHYs`, `gAMA` and `tIME` chunks are handled by
 * [PngMetadata]. HEIC and other formats are read-only here, so their edits
 * stay in the sidecar. IPTC and the ICC profile are also read-only: IPTC edits
 * are mirrored into XMP, and the ICC description is kept beside the file.
 */
object ImageMetadata {

    const val ICC_KEY = MetadataKeys.ICC_PREFIX + "profile"

    private const val GPS_LATITUDE = MetadataKeys.EXIF_PREFIX + "GPSLatitude"
    private const val GPS_LONGITUDE = MetadataKeys.EXIF_PREFIX + "GPSLongitude"
    private const val GPS_ALTITUDE = MetadataKeys.EXIF_PREFIX + "GPSAltitude"

    /** Ordered exactly as the info dialog shows image metadata. */
    val SLOTS = listOf(
        ImageFieldSpec(MetadataKeys.EXIF_PREFIX + "Make", ExifInterface.TAG_MAKE, XmpProperties.TIFF_MAKE),
        ImageFieldSpec(MetadataKeys.EXIF_PREFIX + "Model", ExifInterface.TAG_MODEL, XmpProperties.TIFF_MODEL),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "LensModel",
            ExifInterface.TAG_LENS_MODEL,
            XmpProperties.EXIF_EX_LENS_MODEL,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "DateTimeOriginal",
            ExifInterface.TAG_DATETIME_ORIGINAL,
            XmpProperties.EXIF_DATETIME_ORIGINAL,
            kind = MetadataValueKind.DATE_TIME,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "DateTime",
            ExifInterface.TAG_DATETIME,
            XmpProperties.XMP_MODIFY_DATE,
            kind = MetadataValueKind.DATE_TIME,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "ExposureTime",
            ExifInterface.TAG_EXPOSURE_TIME,
            XmpProperties.EXIF_EXPOSURE_TIME,
        ),
        ImageFieldSpec(MetadataKeys.EXIF_PREFIX + "FNumber", ExifInterface.TAG_F_NUMBER, XmpProperties.EXIF_F_NUMBER),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "ISOSpeedRatings",
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            XmpProperties.EXIF_ISO_SPEED_RATINGS,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "FocalLength",
            ExifInterface.TAG_FOCAL_LENGTH,
            XmpProperties.EXIF_FOCAL_LENGTH,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "Orientation",
            ExifInterface.TAG_ORIENTATION,
            XmpProperties.TIFF_ORIENTATION,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "ImageWidth",
            ExifInterface.TAG_IMAGE_WIDTH,
            XmpProperties.TIFF_IMAGE_WIDTH,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "ImageLength",
            ExifInterface.TAG_IMAGE_LENGTH,
            XmpProperties.TIFF_IMAGE_LENGTH,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "XResolution",
            ExifInterface.TAG_X_RESOLUTION,
            XmpProperties.TIFF_X_RESOLUTION,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "YResolution",
            ExifInterface.TAG_Y_RESOLUTION,
            XmpProperties.TIFF_Y_RESOLUTION,
        ),
        ImageFieldSpec(
            MetadataKeys.EXIF_PREFIX + "ColorSpace",
            ExifInterface.TAG_COLOR_SPACE,
            XmpProperties.EXIF_COLOR_SPACE,
        ),
        ImageFieldSpec(MetadataKeys.EXIF_PREFIX + "Software", ExifInterface.TAG_SOFTWARE, XmpProperties.XMP_CREATOR_TOOL),
        ImageFieldSpec(GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE),
        ImageFieldSpec(GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE),
        ImageFieldSpec(GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE),
        ImageFieldSpec(
            MetadataKeys.XMP_PREFIX + XmpProperties.DC_DESCRIPTION,
            xmpKey = XmpProperties.DC_DESCRIPTION,
            iptcDataset = 120,
            fallbackExifTag = ExifInterface.TAG_IMAGE_DESCRIPTION,
        ),
        ImageFieldSpec(
            MetadataKeys.XMP_PREFIX + XmpProperties.DC_SUBJECT,
            xmpKey = XmpProperties.DC_SUBJECT,
            iptcDataset = 25,
        ),
        ImageFieldSpec(
            MetadataKeys.XMP_PREFIX + XmpProperties.DC_CREATOR,
            xmpKey = XmpProperties.DC_CREATOR,
            iptcDataset = 80,
            fallbackExifTag = ExifInterface.TAG_ARTIST,
        ),
        ImageFieldSpec(
            MetadataKeys.XMP_PREFIX + XmpProperties.DC_RIGHTS,
            xmpKey = XmpProperties.DC_RIGHTS,
            iptcDataset = 116,
            fallbackExifTag = ExifInterface.TAG_COPYRIGHT,
        ),
        ImageFieldSpec(
            MetadataKeys.XMP_PREFIX + XmpProperties.PHOTOSHOP_HEADLINE,
            xmpKey = XmpProperties.PHOTOSHOP_HEADLINE,
            iptcDataset = 105,
        ),
        ImageFieldSpec(
            MetadataKeys.XMP_PREFIX + XmpProperties.PHOTOSHOP_CITY,
            xmpKey = XmpProperties.PHOTOSHOP_CITY,
            iptcDataset = 90,
        ),
        ImageFieldSpec(
            MetadataKeys.XMP_PREFIX + XmpProperties.PHOTOSHOP_COUNTRY,
            xmpKey = XmpProperties.PHOTOSHOP_COUNTRY,
            iptcDataset = 101,
        ),
        ImageFieldSpec(MetadataKeys.XMP_PREFIX + XmpProperties.XMP_RATING, xmpKey = XmpProperties.XMP_RATING),
        ImageFieldSpec(ICC_KEY),
    )

    /** EXIF tags shown after the ordered slots when the file contains them. */
    private val EXTRA_EXIF = listOf(
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
    )

    private val slotsByKey = SLOTS.associateBy { it.key }

    data class WriteResult(
        /** False when the format cannot be rewritten at all. */
        val supported: Boolean,
        /** False when only the sidecar could keep some of the edits. */
        val complete: Boolean,
    )

    fun read(file: File): List<MetadataField> {
        val exif = open(file)
        val xmpText = runCatching { exif?.getAttribute(ExifInterface.TAG_XMP) }.getOrNull()
        val xmp = xmpText?.let { Xmp.read(it) }.orEmpty()
        val iptcEntries = IptcMetadata.readJpeg(file)
        val iptc = iptcEntries.associateBy { it.dataset }
        val pngFields = PngMetadata.read(file)
        val icc = IccProfile.read(file)

        val consumedXmp = mutableSetOf<String>()
        val consumedIptc = mutableSetOf<Int>()
        val fields = mutableListOf<MetadataField>()

        SLOTS.forEach { slot ->
            val raw = when (slot.key) {
                ICC_KEY -> icc?.description
                    ?: icc?.let { "ICC profile (${it.sizeBytes} bytes)" }
                    ?: ""

                GPS_LATITUDE, GPS_LONGITUDE -> {
                    val coordinates = FloatArray(2)
                    if (exif?.getLatLong(coordinates) == true) {
                        formatDecimal(
                            if (slot.key == GPS_LATITUDE) {
                                coordinates[0].toDouble()
                            } else {
                                coordinates[1].toDouble()
                            },
                            6,
                        )
                    } else {
                        ""
                    }
                }

                GPS_ALTITUDE -> exif
                    ?.getAltitude(Double.NaN)
                    ?.takeUnless { it.isNaN() }
                    ?.let { formatDecimal(it, 3) }
                    ?: ""

                else -> {
                    val exifValue = slot.exifTag?.let { tag -> exifText(exif, tag) }
                    val xmpValue = slot.xmpKey?.let { key -> xmp[key] }
                    val iptcValue = slot.iptcDataset?.let { dataset -> iptc[dataset]?.value }
                    val fallback = slot.fallbackExifTag?.let { tag -> exifText(exif, tag) }
                    if (slot.key.startsWith(MetadataKeys.XMP_PREFIX)) {
                        xmpValue ?: iptcValue ?: fallback
                    } else {
                        exifValue ?: xmpValue ?: iptcValue ?: fallback
                    }
                }
            }

            slot.xmpKey?.let { consumedXmp += it }
            slot.iptcDataset?.let { consumedIptc += it }

            val normalised = if (slot.kind == MetadataValueKind.DATE_TIME) {
                DocumentDates.normaliseFlexible(raw) ?: raw.orEmpty()
            } else {
                raw.orEmpty()
            }
            val kind = if (slot.kind == MetadataValueKind.DATE_TIME) {
                MetadataSchema.kindFor(slot.key, normalised)
            } else {
                MetadataValueKind.TEXT
            }
            fields += MetadataField(slot.key, normalised, kind)
        }

        EXTRA_EXIF.forEach { tag ->
            val value = exifText(exif, tag) ?: return@forEach
            val field = MetadataField(
                MetadataKeys.EXIF_PREFIX + tag,
                value,
                MetadataSchema.kindFor(tag, value),
            )
            if (fields.none { it.key == field.key }) fields += field
        }

        xmp.forEach { (key, value) ->
            if (key !in consumedXmp && value.isNotBlank()) {
                val fieldKey = MetadataKeys.XMP_PREFIX + key
                if (fields.none { it.key == fieldKey }) {
                    fields += MetadataField(
                        fieldKey,
                        value,
                        MetadataSchema.kindFor(key, value),
                    )
                }
            }
        }

        iptcEntries.forEach { entry ->
            if (entry.dataset !in consumedIptc && entry.value.isNotBlank()) {
                val fieldKey = MetadataKeys.IPTC_PREFIX + entry.name
                if (fields.none { it.key == fieldKey }) {
                    fields += MetadataField(
                        fieldKey,
                        entry.value,
                        MetadataSchema.kindFor(entry.name, entry.value),
                    )
                }
            }
        }

        pngFields.forEach { field ->
            if (fields.none { it.key == field.key }) fields += field
        }

        return fields
    }

    /**
     * Writes [fields] into [file]. Callers pass only the fields the reader
     * changed, so untouched metadata is not rewritten.
     */
    fun write(
        file: File,
        fields: List<MetadataField>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): WriteResult {
        if (fields.isEmpty()) return WriteResult(supported = true, complete = true)

        val jpeg = isJpeg(file)
        val png = PngMetadata.isPng(file)
        val webp = isWebp(file)
        if (!jpeg && !png && !webp) return WriteResult(supported = false, complete = false)

        val exif = runCatching { ExifInterface(file) }.getOrNull()
            ?: return WriteResult(supported = true, complete = false)

        var complete = true
        val exifUpdates = linkedMapOf<String, String?>()
        val xmpUpdates = linkedMapOf<String, String?>()
        val pngUpdates = mutableListOf<MetadataField>()

        var latitude: Double? = null
        var longitude: Double? = null
        var altitude: Double? = null
        var positionTouched = false
        var altitudeTouched = false

        fields.forEach { field ->
            val value = field.value.trim().takeIf { it.isNotEmpty() }
            val slot = slotsByKey[field.key]
            when {
                field.key == ICC_KEY -> complete = false

                field.key == GPS_LATITUDE -> {
                    latitude = value?.toDoubleOrNull()
                    positionTouched = true
                    if (value != null && latitude == null) complete = false
                }

                field.key == GPS_LONGITUDE -> {
                    longitude = value?.toDoubleOrNull()
                    positionTouched = true
                    if (value != null && longitude == null) complete = false
                }

                field.key == GPS_ALTITUDE -> {
                    altitude = value?.toDoubleOrNull()
                    altitudeTouched = true
                    if (value != null && altitude == null) complete = false
                }

                slot?.exifTag != null -> {
                    val exifValue = if (field.kind == MetadataValueKind.DATE_TIME) {
                        value?.let { DocumentDates.toExifDate(it, timeZone) }
                    } else {
                        value
                    }
                    exifUpdates[slot.exifTag] = exifValue
                    slot.xmpKey?.let { key ->
                        xmpUpdates[key] = xmpValue(field, value, timeZone)
                    }
                }

                slot?.xmpKey != null -> {
                    xmpUpdates[slot.xmpKey] = xmpValue(field, value, timeZone)
                }

                field.key.startsWith(MetadataKeys.EXIF_PREFIX) -> {
                    val tag = field.key.removePrefix(MetadataKeys.EXIF_PREFIX)
                    exifUpdates[tag] = if (field.kind == MetadataValueKind.DATE_TIME) {
                        value?.let { DocumentDates.toExifDate(it, timeZone) }
                    } else {
                        value
                    }
                }

                field.key.startsWith(MetadataKeys.XMP_PREFIX) -> {
                    val key = field.key.removePrefix(MetadataKeys.XMP_PREFIX)
                    xmpUpdates[key] = xmpValue(field, value, timeZone)
                }

                field.key.startsWith(MetadataKeys.PNG_PREFIX) -> {
                    pngUpdates += field.copy(value = value.orEmpty())
                }

                field.key.startsWith(MetadataKeys.IPTC_PREFIX) -> complete = false
            }
        }

        try {
            exifUpdates.forEach { (tag, value) -> exif.setAttribute(tag, value) }

            if (positionTouched) {
                if (latitude != null && longitude != null) {
                    exif.setLatLong(latitude, longitude)
                } else if (latitude == null && longitude == null) {
                    listOf(
                        ExifInterface.TAG_GPS_LATITUDE,
                        ExifInterface.TAG_GPS_LATITUDE_REF,
                        ExifInterface.TAG_GPS_LONGITUDE,
                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ).forEach { exif.setAttribute(it, null) }
                } else {
                    complete = false
                }
            }

            if (altitudeTouched) {
                if (altitude != null) {
                    exif.setAltitude(altitude)
                } else {
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
                }
            }

            if (xmpUpdates.isNotEmpty()) {
                val existing = runCatching {
                    exif.getAttribute(ExifInterface.TAG_XMP)
                }.getOrNull()
                val updated = Xmp.update(existing, xmpUpdates)
                if (updated != existing) exif.setAttribute(ExifInterface.TAG_XMP, updated)
            }

            exif.saveAttributes()
        } catch (_: Exception) {
            return WriteResult(supported = true, complete = false)
        }

        if (png && !PngMetadata.write(file, pngUpdates, timeZone)) complete = false
        return WriteResult(supported = true, complete = complete)
    }

    private fun xmpValue(field: MetadataField, value: String?, timeZone: TimeZone): String? =
        if (field.kind == MetadataValueKind.DATE_TIME) {
            value?.let { DocumentDates.normaliseFlexible(it, timeZone) }
        } else {
            value
        }

    private fun open(file: File): ExifInterface? =
        runCatching { ExifInterface(file) }.getOrNull()

    private fun exifText(exif: ExifInterface?, tag: String): String? =
        runCatching { exif?.getAttribute(tag) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun formatDecimal(value: Double, scale: Int): String =
        BigDecimal.valueOf(value)
            .setScale(scale, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    private fun isJpeg(file: File): Boolean = signature(file, 0xFF, 0xD8, 0xFF)

    private fun isWebp(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val head = ByteArray(12)
            if (stream.read(head) != head.size) return@use false
            String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        }
    }.getOrDefault(false)

    private fun signature(file: File, vararg values: Int): Boolean = runCatching {
        file.inputStream().use { stream ->
            val head = ByteArray(values.size)
            if (stream.read(head) != head.size) return@use false
            values.indices.all { (head[it].toInt() and 0xFF) == values[it] }
        }
    }.getOrDefault(false)
}
