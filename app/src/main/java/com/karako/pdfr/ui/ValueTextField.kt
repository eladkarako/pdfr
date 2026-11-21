package com.karako.pdfr.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection

/** Monospace for every editable value, so ranges and numbers line up. */
@Composable
internal fun monospaceFieldStyle(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)

/**
 * A text field for machine-readable values such as page ranges, sizes, dates
 * and extensions. The text is always left-aligned and laid out left-to-right,
 * even in an RTL language: `1-3,7,9-e` must not have its commas or `e` moved to
 * the other side. The field itself keeps the surrounding layout direction, so
 * labels and supporting text still follow the language.
 */
@Composable
internal fun ValueTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            singleLine = singleLine,
            label = label,
            placeholder = placeholder,
            isError = isError,
            supportingText = supportingText,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            textStyle = monospaceFieldStyle().copy(textAlign = TextAlign.Left),
        )
    }
}
