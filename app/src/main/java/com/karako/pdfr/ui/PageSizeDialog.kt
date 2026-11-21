package com.karako.pdfr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.karako.pdfr.R
import com.karako.pdfr.data.IsoPaperSize
import com.karako.pdfr.data.PageRanges
import com.karako.pdfr.data.PageSize
import com.karako.pdfr.data.PageSizeUnit

/**
 * Chooses a new page size for the listed pages.
 *
 * The paper list covers the ISO A, B and C series; editing either dimension
 * switches the dialog to a custom size. The page box is expressed in points,
 * so millimetres, centimetres, inches, CSS pixels and 12 pt `em` units all
 * resolve to the same value on every screen.
 */
@Composable
internal fun PageSizeDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onApply: (IntArray, Float, Float) -> Unit,
) {
    var range by rememberSaveable { mutableStateOf(PageRanges.ALL_PAGES) }
    var unitName by rememberSaveable { mutableStateOf(PageSizeUnit.MILLIMETERS.name) }
    val unit = PageSizeUnit.valueOf(unitName)
    var paperName by rememberSaveable { mutableStateOf(IsoPaperSize.A4.name) }
    var widthText by rememberSaveable {
        mutableStateOf(PageSize.format(PageSizeUnit.MILLIMETERS.fromPoints(IsoPaperSize.A4.widthPt)))
    }
    var heightText by rememberSaveable {
        mutableStateOf(PageSize.format(PageSizeUnit.MILLIMETERS.fromPoints(IsoPaperSize.A4.heightPt)))
    }
    var paperMenuOpen by remember { mutableStateOf(false) }
    var unitMenuOpen by remember { mutableStateOf(false) }

    val rangeResult = remember(range, pageCount) { PageRanges.parse(range, pageCount) }
    val pages = (rangeResult as? PageRanges.Result.Ok)?.pages
    val rangeError = rangeResult as? PageRanges.Result.Error
    val widthPt = PageSize.pointsOrNull(widthText, unit)
    val heightPt = PageSize.pointsOrNull(heightText, unit)
    val sizeValid = widthPt != null && heightPt != null

    fun apply() {
        val w = widthPt ?: return
        val h = heightPt ?: return
        pages?.takeIf { it.isNotEmpty() }?.let { onApply(it, w, h) }
    }

    fun selectPaper(paper: IsoPaperSize) {
        paperName = paper.name
        widthText = PageSize.format(unit.fromPoints(paper.widthPt))
        heightText = PageSize.format(unit.fromPoints(paper.heightPt))
    }

    fun selectUnit(next: PageSizeUnit) {
        if (next == unit) return
        PageSize.numberOrNull(widthText)?.let {
            widthText = PageSize.format(next.fromPoints(unit.toPoints(it)))
        }
        PageSize.numberOrNull(heightText)?.let {
            heightText = PageSize.format(next.fromPoints(unit.toPoints(it)))
        }
        unitName = next.name
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_size_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = dimensionResource(R.dimen.normalize_dialog_max_height))
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.page_size_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                ValueTextField(
                    value = range,
                    onValueChange = { range = it.take(PageRanges.MAX_INPUT_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.page_size_hint)) },
                    isError = rangeError != null,
                    supportingText = {
                        when {
                            rangeError != null -> Text(pageRangeError(rangeError, pageCount))
                            pages != null -> Text(
                                pluralStringResource(
                                    R.plurals.page_size_selected,
                                    pages.size,
                                    pages.size,
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { apply() }),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.page_size_paper),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                Box {
                    OutlinedButton(
                        onClick = { paperMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (paperName.isEmpty()) {
                                stringResource(R.string.page_size_custom)
                            } else {
                                paperName
                            },
                        )
                    }
                    DropdownMenu(
                        expanded = paperMenuOpen,
                        onDismissRequest = { paperMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.page_size_custom)) },
                            onClick = {
                                paperMenuOpen = false
                                paperName = ""
                            },
                        )
                        IsoPaperSize.entries.forEach { paper ->
                            DropdownMenuItem(
                                text = { Text(paper.name) },
                                onClick = {
                                    paperMenuOpen = false
                                    selectPaper(paper)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ValueTextField(
                        value = widthText,
                        onValueChange = {
                            widthText = it.take(MAX_DIMENSION_LENGTH)
                            paperName = ""
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.page_size_width)) },
                        isError = widthText.isNotBlank() && widthPt == null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    ValueTextField(
                        value = heightText,
                        onValueChange = {
                            heightText = it.take(MAX_DIMENSION_LENGTH)
                            paperName = ""
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.page_size_height)) },
                        isError = heightText.isNotBlank() && heightPt == null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { apply() }),
                    )
                    Box {
                        OutlinedButton(onClick = { unitMenuOpen = true }) {
                            Text(unit.suffix)
                        }
                        DropdownMenu(
                            expanded = unitMenuOpen,
                            onDismissRequest = { unitMenuOpen = false },
                        ) {
                            PageSizeUnit.entries.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate.suffix) },
                                    onClick = {
                                        unitMenuOpen = false
                                        selectUnit(candidate)
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (widthPt != null && heightPt != null) {
                    Text(
                        text = stringResource(
                            R.string.page_size_points,
                            PageSize.format(widthPt),
                            PageSize.format(heightPt),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.page_size_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.rewrite_replace_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::apply,
                enabled = sizeValid && pages != null && pages.isNotEmpty(),
            ) {
                Text(stringResource(R.string.page_size_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private const val MAX_DIMENSION_LENGTH = 12
