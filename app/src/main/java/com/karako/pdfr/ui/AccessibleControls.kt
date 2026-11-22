package com.karako.pdfr.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
            .classicButton(
                interaction = interaction,
                shape = CircleShape,
                enabled = enabled,
                active = active,
            ),
        interactionSource = interaction,
        content = content,
    )
}

/**
 * The raised-key look shared by the toolbar and page-bar buttons.
 *
 * At rest the control sits on a tinted surface with a hairline outline and a
 * small shadow. While pressed it shrinks, moves down a couple of pixels and
 * loses the shadow, then springs back when released. Focus or hover adds the
 * primary ring used for keyboard and TV navigation. The whole effect is drawn
 * from the button's interaction source, so it works for icon buttons, text
 * buttons and the overflow menu trigger alike without any bitmap assets.
 */
@Composable
internal fun Modifier.classicButton(
    interaction: MutableInteractionSource,
    shape: Shape,
    enabled: Boolean,
    active: Boolean,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val pushed = pressed && enabled
    val scale by animateFloatAsState(
        targetValue = if (pushed) 0.9f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "button-scale",
    )
    val shadow by animateDpAsState(
        targetValue = if (enabled && !pushed) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 90),
        label = "button-shadow",
    )
    val lift by animateDpAsState(
        targetValue = if (pushed) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 90),
        label = "button-lift",
    )
    val fill = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        pushed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val borderColor = if (active && enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.6f else 0.25f)
    }

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = lift.toPx()
        }
        .shadow(elevation = shadow, shape = shape, clip = false)
        .background(color = fill, shape = shape)
        .border(
            width = if (active && enabled) 2.dp else 1.dp,
            color = borderColor,
            shape = shape,
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
