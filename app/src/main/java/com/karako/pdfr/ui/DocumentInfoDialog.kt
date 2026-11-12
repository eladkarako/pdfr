package com.karako.pdfr.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.karako.pdfr.R
import com.karako.pdfr.data.DocumentDates
import com.karako.pdfr.data.DocumentInfo
import com.karako.pdfr.data.DocumentMetadataDraft
import com.karako.pdfr.data.MetadataKeys
import com.karako.pdfr.data.MetadataValueKind
import java.util.Calendar
import java.util.TimeZone

private const val INFO_DIALOG_MAX_HEIGHT_FRACTION = 0.75f

/**
 * The editable replacement for the old read-only info sheet.
 *
 * Everything that can be written back is edited in a [DocumentMetadataDraft]
 * owned by the ViewModel, so closing the dialog (tap outside or Back) keeps
 * unsaved edits until the reader returns and reopens it. Cancel throws the
 * draft away, Apply writes it to the cache file.
 *
 * Only properties the document already has are shown. Clearing a field keeps
 * the field but removes the value, which is exactly what Apply writes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentInfoDialog(
    info: DocumentInfo,
    draft: DocumentMetadataDraft,
    busy: Boolean,
    onDraftChange: (DocumentMetadataDraft) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val context = LocalContext.current
    val maxHeight = (
            LocalConfiguration.current.screenHeightDp.dp * INFO_DIALOG_MAX_HEIGHT_FRACTION
            ).coerceAtLeast(260.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column {
                // Apply and Cancel sit above the fields, so a long metadata
                // list never hides the action the reader came for.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.document_info),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = onApply,
                        enabled = draft.isValid && !busy,
                    ) {
                        Text(stringResource(R.string.apply))
                    }
                }
                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    SectionHeader(stringResource(R.string.file_properties))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            ReadOnlyRow(
                                label = stringResource(R.string.file_size),
                                value = Formatter.formatFileSize(context, info.sizeBytes),
                            )
                            ReadOnlyRow(
                                label = stringResource(R.string.document_type),
                                value = info.kind,
                            )
                            info.format?.let { format ->
                                if (!format.equals(info.kind, ignoreCase = true)) {
                                    ReadOnlyRow(
                                        label = stringResource(R.string.document_format),
                                        value = format,
                                    )
                                }
                            }
                            ReadOnlyRow(
                                label = stringResource(R.string.page_count),
                                value = pluralStringResource(
                                    R.plurals.page_count_value,
                                    info.pageCount,
                                    info.pageCount,
                                ),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        OutlinedTextField(
                            value = draft.fileName,
                            onValueChange = {
                                onDraftChange(draft.copy(fileName = it.take(200)))
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(stringResource(R.string.file_name)) },
                            isError = !draft.fileNameValid,
                            supportingText = if (!draft.fileNameValid) {
                                { Text(stringResource(R.string.file_name_required)) }
                            } else {
                                null
                            },
                        )
                        OutlinedTextField(
                            value = draft.extension,
                            onValueChange = {
                                onDraftChange(draft.copy(extension = it.take(12)))
                            },
                            modifier = Modifier.width(112.dp),
                            singleLine = true,
                            label = { Text(stringResource(R.string.file_extension)) },
                            isError = !draft.extensionValid,
                            supportingText = if (!draft.extensionValid) {
                                { Text(stringResource(R.string.file_extension_hint)) }
                            } else {
                                null
                            },
                        )
                    }

                    DateTimeField(
                        label = stringResource(R.string.file_created),
                        value = draft.fileCreated.orEmpty(),
                        onChange = { onDraftChange(draft.copy(fileCreated = it)) },
                    )
                    DateTimeField(
                        label = stringResource(R.string.file_modified),
                        value = draft.fileModified.orEmpty(),
                        onChange = { onDraftChange(draft.copy(fileModified = it)) },
                    )

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    SectionHeader(stringResource(R.string.document_metadata))

                    draft.fields.forEach { field ->
                        val label = metadataLabel(field.key)
                        val onChange: (String) -> Unit = { value ->
                            onDraftChange(
                                draft.copy(
                                    fields = draft.fields.map {
                                        if (it.key == field.key) it.copy(value = value) else it
                                    },
                                ),
                            )
                        }
                        if (field.kind == MetadataValueKind.DATE_TIME) {
                            DateTimeField(
                                label = label,
                                value = field.value,
                                onChange = onChange,
                            )
                        } else {
                            MetadataTextField(
                                label = label,
                                value = field.value,
                                onChange = onChange,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.metadata_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun metadataLabel(key: String): String = when (key) {
    MetadataKeys.TITLE -> stringResource(R.string.metadata_title)
    MetadataKeys.AUTHOR -> stringResource(R.string.metadata_author)
    MetadataKeys.SUBJECT -> stringResource(R.string.metadata_subject)
    MetadataKeys.KEYWORDS -> stringResource(R.string.metadata_keywords)
    MetadataKeys.CREATOR -> stringResource(R.string.metadata_creator)
    MetadataKeys.PRODUCER -> stringResource(R.string.metadata_producer)
    MetadataKeys.CREATION_DATE -> stringResource(R.string.metadata_created)
    MetadataKeys.MODIFIED_DATE -> stringResource(R.string.metadata_modified)
    MetadataKeys.EXIF_PREFIX + "Make" -> stringResource(R.string.metadata_make)
    MetadataKeys.EXIF_PREFIX + "Model" -> stringResource(R.string.metadata_model)
    MetadataKeys.EXIF_PREFIX + "LensModel" -> stringResource(R.string.metadata_lens_model)
    MetadataKeys.EXIF_PREFIX + "DateTimeOriginal" -> stringResource(R.string.metadata_datetime_original)
    MetadataKeys.EXIF_PREFIX + "DateTime" -> stringResource(R.string.metadata_datetime)
    MetadataKeys.EXIF_PREFIX + "ExposureTime" -> stringResource(R.string.metadata_exposure_time)
    MetadataKeys.EXIF_PREFIX + "FNumber" -> stringResource(R.string.metadata_f_number)
    MetadataKeys.EXIF_PREFIX + "ISOSpeedRatings" -> stringResource(R.string.metadata_iso)
    MetadataKeys.EXIF_PREFIX + "FocalLength" -> stringResource(R.string.metadata_focal_length)
    MetadataKeys.EXIF_PREFIX + "Orientation" -> stringResource(R.string.metadata_orientation)
    MetadataKeys.EXIF_PREFIX + "ImageWidth" -> stringResource(R.string.metadata_image_width)
    MetadataKeys.EXIF_PREFIX + "ImageLength" -> stringResource(R.string.metadata_image_length)
    MetadataKeys.EXIF_PREFIX + "XResolution" -> stringResource(R.string.metadata_x_resolution)
    MetadataKeys.EXIF_PREFIX + "YResolution" -> stringResource(R.string.metadata_y_resolution)
    MetadataKeys.EXIF_PREFIX + "ColorSpace" -> stringResource(R.string.metadata_color_space)
    MetadataKeys.EXIF_PREFIX + "Software" -> stringResource(R.string.metadata_software)
    MetadataKeys.EXIF_PREFIX + "GPSLatitude" -> stringResource(R.string.metadata_gps_latitude)
    MetadataKeys.EXIF_PREFIX + "GPSLongitude" -> stringResource(R.string.metadata_gps_longitude)
    MetadataKeys.EXIF_PREFIX + "GPSAltitude" -> stringResource(R.string.metadata_gps_altitude)
    MetadataKeys.XMP_PREFIX + "dc:description" -> stringResource(R.string.metadata_caption)
    MetadataKeys.XMP_PREFIX + "dc:subject" -> stringResource(R.string.metadata_keywords)
    MetadataKeys.XMP_PREFIX + "dc:creator" -> stringResource(R.string.metadata_creator)
    MetadataKeys.XMP_PREFIX + "dc:rights" -> stringResource(R.string.metadata_copyright)
    MetadataKeys.XMP_PREFIX + "photoshop:Headline" -> stringResource(R.string.metadata_headline)
    MetadataKeys.XMP_PREFIX + "photoshop:City" -> stringResource(R.string.metadata_city)
    MetadataKeys.XMP_PREFIX + "photoshop:Country" -> stringResource(R.string.metadata_country)
    MetadataKeys.XMP_PREFIX + "xmp:Rating" -> stringResource(R.string.metadata_rating)
    MetadataKeys.ICC_PREFIX + "profile" -> stringResource(R.string.metadata_icc_profile)
    else -> when {
        key.startsWith(MetadataKeys.INFO_PREFIX) ->
            key.removePrefix(MetadataKeys.INFO_PREFIX) + " (Info)"

        key.startsWith(MetadataKeys.XMP_PREFIX) ->
            key.removePrefix(MetadataKeys.XMP_PREFIX) + " (XMP)"

        key.startsWith(MetadataKeys.EXIF_PREFIX) ->
            key.removePrefix(MetadataKeys.EXIF_PREFIX) + " (EXIF)"

        key.startsWith(MetadataKeys.PNG_PREFIX) ->
            key.removePrefix(MetadataKeys.PNG_PREFIX) + " (PNG)"

        key.startsWith(MetadataKeys.IPTC_PREFIX) ->
            key.removePrefix(MetadataKeys.IPTC_PREFIX) + " (IPTC)"

        key.startsWith(MetadataKeys.ICC_PREFIX) ->
            key.removePrefix(MetadataKeys.ICC_PREFIX) + " (ICC)"

        key.startsWith(MetadataKeys.GENERIC_PREFIX) ->
            key.removePrefix(MetadataKeys.GENERIC_PREFIX)

        else -> key
    }
}

@Composable
private fun MetadataTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(2000)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        label = { Text(label) },
    )
}

private enum class PickerMode { DATE, TIME }

/**
 * An ISO date/time field with date and time pickers. The text stays editable
 * so a value can be typed or pasted; the pickers are a convenience and always
 * write the same `yyyy-MM-ddThh:mm:ss` shape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    val millis = DocumentDates.parseIso(value)
    val invalid = value.isNotBlank() && millis == null
    var picker by remember { mutableStateOf<PickerMode?>(null) }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        // The field takes the full width so the whole ISO value stays visible;
        // the picker buttons sit on their own row underneath.
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.take(19)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text("yyyy-MM-ddThh:mm:ss") },
            isError = invalid,
            supportingText = if (invalid) {
                { Text(stringResource(R.string.date_invalid)) }
            } else {
                null
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { picker = PickerMode.DATE }) {
                Text(stringResource(R.string.pick_date))
            }
            TextButton(
                onClick = { picker = PickerMode.TIME },
                enabled = millis != null,
            ) {
                Text(stringResource(R.string.pick_time))
            }
            if (value.isNotEmpty()) {
                TextButton(onClick = { onChange("") }) {
                    Text(stringResource(R.string.clear_value))
                }
            }
        }
    }

    when (picker) {
        PickerMode.DATE -> {
            val base = millis ?: System.currentTimeMillis()
            val state = rememberDatePickerState(
                initialSelectedDateMillis = toUtcDate(base),
            )
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val selected = state.selectedDateMillis ?: toUtcDate(base)
                            onChange(
                                DocumentDates.formatIso(combineDate(selected, base)),
                            )
                            picker = null
                        },
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { picker = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            ) {
                DatePicker(state = state)
            }
        }

        PickerMode.TIME -> {
            val base = millis ?: System.currentTimeMillis()
            val local = Calendar.getInstance().apply { timeInMillis = base }
            val state = rememberTimePickerState(
                initialHour = local.get(Calendar.HOUR_OF_DAY),
                initialMinute = local.get(Calendar.MINUTE),
                is24Hour = true,
            )
            AlertDialog(
                onDismissRequest = { picker = null },
                title = { Text(stringResource(R.string.time_picker_title)) },
                text = { TimePicker(state = state) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onChange(
                                DocumentDates.formatIso(
                                    combineTime(base, state.hour, state.minute),
                                ),
                            )
                            picker = null
                        },
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { picker = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        null -> Unit
    }
}

/**
 * Material's date picker speaks UTC midnights. These helpers bridge that to
 * the device's local date and time, which is what the ISO field shows.
 */
private fun toUtcDate(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH),
            local.get(Calendar.DAY_OF_MONTH),
        )
    }.timeInMillis
}

private fun combineDate(utcDateMillis: Long, localTimeMillis: Long): Long {
    val date = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcDateMillis
    }
    val time = Calendar.getInstance().apply { timeInMillis = localTimeMillis }
    return Calendar.getInstance().apply {
        clear()
        set(
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH),
            date.get(Calendar.DAY_OF_MONTH),
            time.get(Calendar.HOUR_OF_DAY),
            time.get(Calendar.MINUTE),
            0,
        )
    }.timeInMillis
}

private fun combineTime(localMillis: Long, hour: Int, minute: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = localMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
