package com.karako.pdfr.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.karako.pdfr.R
import com.karako.pdfr.data.TextSettings
import kotlin.math.roundToInt

/**
 * Chooses the text size used by reflowable documents. The value is in points,
 * so it has the same meaning on every screen density; PDFs and images are
 * fixed-layout and keep their own pinch zoom instead.
 */
@Composable
internal fun TextSizeDialog(
    current: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var value by rememberSaveable(current) { mutableFloatStateOf(current.toFloat()) }
    val points = value.roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_size_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.text_size_value, points),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = TextSettings.MIN_SIZE_PT.toFloat()..
                            TextSettings.MAX_SIZE_PT.toFloat(),
                    steps = TextSettings.MAX_SIZE_PT - TextSettings.MIN_SIZE_PT - 1,
                )
                Text(
                    text = stringResource(R.string.text_size_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(points) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
