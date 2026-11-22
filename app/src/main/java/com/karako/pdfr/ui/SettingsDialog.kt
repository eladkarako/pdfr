package com.karako.pdfr.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karako.pdfr.R
import com.karako.pdfr.data.AppLanguage
import com.karako.pdfr.data.AppSettings
import com.karako.pdfr.data.PageDirection
import com.karako.pdfr.data.ThemeMode

/**
 * App-wide appearance and language settings.
 *
 * The theme choice applies as soon as a row is tapped. The language choice is
 * a closed combo box rather than a radio list, which keeps the dialog short;
 * its entries show the English name and the language's own name side by side,
 * so every language can be recognised whichever UI language is active. The
 * rows come from the generated `supported_language_tags` array, which the build
 * derives from the `values-*` folders. Choosing a language reloads the activity
 * so Android resolves the new resource folder; the document itself is
 * unaffected because the reader keeps its own text direction.
 */
@Composable
internal fun SettingsDialog(
    themeMode: ThemeMode,
    languageTag: String,
    pageDirection: PageDirection,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (String) -> Unit,
    onPageDirectionChange: (PageDirection) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            val context = LocalContext.current
            val languages = remember(context) { AppSettings.languages(context) }
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
                LanguageCombo(
                    selected = AppSettings.language(languages, languageTag),
                    languages = languages,
                    onSelect = { onLanguageChange(it.tag) },
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                SectionTitle(stringResource(R.string.settings_page_direction))
                DirectionCombo(
                    selected = pageDirection,
                    onSelect = onPageDirectionChange,
                )

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

/**
 * A closed combo box for the language override.
 *
 * The trigger shows only the current choice, and tapping it opens the full
 * list. Every real language entry uses two weighted columns: the English name
 * and the language's own name. The system-default entry is a single translated
 * label because it has no language of its own.
 */
@Composable
private fun LanguageCombo(
    selected: AppLanguage,
    languages: List<AppLanguage>,
    onSelect: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        ComboButton(onClick = { expanded = true }) {
            Box(Modifier.weight(1f)) {
                LanguageCaption(selected)
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(dimensionResource(R.dimen.language_menu_width)),
        ) {
            languages.forEach { language ->
                AccessibleDropdownMenuItem(
                    text = { LanguageCaption(language) },
                    onClick = {
                        expanded = false
                        if (language.tag != selected.tag) onSelect(language)
                    },
                    leadingIcon = {
                        // A fixed slot for every row keeps the caption columns
                        // aligned whether or not this row is the current one.
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (language.tag == selected.tag) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * The closed combo box for the page-bar direction. It uses the same trigger as
 * the language picker, so both rows share one focus ring and one press feel.
 */
@Composable
private fun DirectionCombo(
    selected: PageDirection,
    onSelect: (PageDirection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        ComboButton(onClick = { expanded = true }) {
            Box(Modifier.weight(1f)) {
                Text(
                    text = stringResource(selected.labelRes()),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(dimensionResource(R.dimen.language_menu_width)),
        ) {
            PageDirection.entries.forEach { direction ->
                AccessibleDropdownMenuItem(
                    text = { Text(stringResource(direction.labelRes())) },
                    onClick = {
                        expanded = false
                        if (direction != selected) onSelect(direction)
                    },
                    leadingIcon = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (direction == selected) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/** The shared closed-combo trigger: border, focus ring and dropdown arrow slot. */
@Composable
private fun ComboButton(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
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
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent,
                shape,
            )
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.DropdownList,
                onClick = onClick,
            )
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** English name and native name, side by side in two aligned columns. */
@Composable
private fun LanguageCaption(language: AppLanguage) {
    val labelRes = language.labelRes
    if (labelRes != null) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val captionStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = language.englishName,
            style = captionStyle,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = language.endonym,
            style = captionStyle,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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

private fun PageDirection.labelRes(): Int = when (this) {
    PageDirection.LANGUAGE -> R.string.page_direction_language
    PageDirection.LTR -> R.string.page_direction_ltr
    PageDirection.RTL -> R.string.page_direction_rtl
}
