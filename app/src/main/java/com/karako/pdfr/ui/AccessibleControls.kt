package com.karako.pdfr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * An [IconButton] that shows a primary-coloured ring when it is focused (for
 * a keyboard or TV remote) or hovered (for a mouse). The ring is drawn from
 * the button's own interaction source, so it uses the same press, focus and
 * hover state as the ripple.
 *
 * [contentDescription] is set on the button rather than on the [Icon] inside
 * it: the focusable node is what a screen reader, switch access or a TV remote
 * announces, so the label has to live there.
 */
@Composable
internal fun AccessibleIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = focused || hovered

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            }
            .then(
                if (active) {
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            CircleShape,
                        )
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                },
            ),
        interactionSource = interaction,
        content = content,
    )
}

/**
 * A [DropdownMenuItem] with the same focus/hover ring as the toolbar buttons.
 * Material3 already paints a menu-item highlight; the outline makes the
 * current target obvious when several items look similar.
 */
@Composable
internal fun AccessibleDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = focused || hovered
    val shape = RoundedCornerShape(8.dp)

    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier
            // Same merge as the toolbar buttons: the row a D-pad lands on
            // should read out its caption, not an empty container.
            .semantics(mergeDescendants = true) {}
            .then(
                if (active) {
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape,
                        )
                        .border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            ),
        leadingIcon = leadingIcon,
        enabled = enabled,
        interactionSource = interaction,
    )
}
