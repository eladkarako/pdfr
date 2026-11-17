package com.karako.pdfr.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.karako.pdfr.R
import com.karako.pdfr.data.AppSettings
import com.karako.pdfr.data.ThemeMode

/**
 * App-wide appearance and language settings.
 *
 * The theme choice applies as soon as a row is tapped. The language choice
 * reloads the activity so Android resolves the new resource folder; the
 * document itself is unaffected because the reader keeps its own text
 * direction.
 */
@Composable
internal fun SettingsDialog(
    themeMode: ThemeMode,
    languageTag: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = dimensionResource(R.dimen.normalize_dialog_max_height))
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionTitle(stringResource(R.string.settings_theme))
                ThemeMode.entries.forEach { mode ->
                    ChoiceRow(
                        selected = mode == themeMode,
                        label = stringResource(mode.labelRes()),
                        onClick = { onThemeModeChange(mode) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                SectionTitle(stringResource(R.string.settings_language))
                AppSettings.languages.forEach { language ->
                    ChoiceRow(
                        selected = language.tag == languageTag,
                        label = AppSettings.displayName(context, language),
                        onClick = { onLanguageChange(language.tag) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * A radio row with the same focus/hover outline as the toolbar buttons, so a
 * keyboard or TV remote can see which target is active.
 */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = focused || hovered
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (active) {
                    Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape)
                        .border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.AMOLED -> R.string.theme_amoled
}
