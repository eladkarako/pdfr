package com.karako.pdfr.ui

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Touch target of a toolbar key, kept at the 48 dp accessibility minimum. */
private val ToolbarKeyTouchSize = 48.dp

/** Visible inset between the touch target and the drawn key cap. */
private val ToolbarKeyInset = 6.dp

/**
 * A toolbar key in the flat, beveled style of a classic Windows toolbar. It has
 * a straight-edged 3-D border at rest and a sunken border while held. The
 * focus/hover state adds a dotted focus rectangle, the same cue a Windows
 * keyboard user expects, so a remote or keyboard target stays obvious.
 *
 * The control keeps the full 48 dp touch target required for accessibility,
 * but only the inset 36 dp cap is drawn. That leaves about 6 dp around a 24 dp
 * icon instead of a large empty square around it.
 *
 * [Modifier.clickable] invokes [onClick] on pointer *release* and on the key-up
 * of Enter/D-pad centre, never on key-down. Holding a finger or a remote key
 * therefore keeps the cap sunken long enough to see the pressed state.
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
    val contentColor = LocalContentColor.current

    Box(
        modifier = modifier
            .size(ToolbarKeyTouchSize)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            }
            // The clickable target stays 48 dp; the padding below shrinks only
            // the drawn key cap.
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(ToolbarKeyInset)
            .classicButton(
                interaction = interaction,
                enabled = enabled,
                active = focused || hovered,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) {
                contentColor
            } else {
                contentColor.copy(alpha = 0.38f)
            },
        ) {
            content()
        }
    }
}

/**
 * The flat, beveled key look shared by the toolbar, page bar and dialogs.
 *
 * This is the Windows 98 push-button bevel, not an approximation: two sharp
 * one-pixel steps in each direction. Raised, the top/left is white and the
 * bottom/right black, with the lighter/darker inner pair one pixel in. Pressed,
 * the pairs swap so the cap reads as sunk into the surface. A default dialog
 * button adds a one-pixel black ring around the whole bevel.
 *
 * Focus draws the classic black dotted rectangle inside the bevel. It stays
 * after the key is released, which is what lets a D-pad user see where the
 * next Enter will land. Edges are drawn with anti-aliasing off on whole device
 * pixels, so they stay crisp at every density instead of turning into a blur.
 */
@Composable
internal fun Modifier.classicButton(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    active: Boolean,
    default: Boolean = false,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val pushed = pressed && enabled
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val baseFace = if (dark) {
        lerp(scheme.surfaceVariant, Color.White, 0.10f)
    } else {
        Color(0xFFC0C0C0)
    }
    val face = if (pushed) lerp(baseFace, Color.Black, 0.05f) else baseFace
    val white = if (dark) lerp(face, Color.White, 0.60f) else Color.White
    val light = if (dark) lerp(face, Color.White, 0.28f) else Color(0xFFDFDFDF)
    val shadow = if (dark) lerp(face, Color.Black, 0.45f) else Color(0xFF808080)
    val black = if (dark) lerp(face, Color.Black, 0.85f) else Color.Black
    val ring = if (dark) white else Color.Black
    val focusColor = if (dark) Color.White else Color.Black
    // A dialog is its own window. The activity below keeps its focused node,
    // so gate the dotted cue on the window actually receiving input; otherwise
    // the toolbar shows a second, stale focus rectangle behind the dialog.
    val windowFocused = LocalWindowInfo.current.isWindowFocused

    val topOuter = if (pushed) black else white
    val topInner = if (pushed) shadow else light
    val bottomOuter = if (pushed) white else black
    val bottomInner = if (pushed) light else shadow

    return drawBehind {
        val px = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
        val right = size.width.roundToInt().toFloat()
        val bottom = size.height.roundToInt().toFloat()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val paint = Paint().apply {
                isAntiAlias = false
                style = Paint.Style.FILL
            }

            native.fill(0f, 0f, right, bottom, face.toArgb(), paint)

            var inset = 0f
            if (default) {
                native.ring(0f, 0f, right, bottom, px, ring.toArgb(), paint)
                inset = px
            }
            native.bevel(
                inset,
                inset,
                right - inset,
                bottom - inset,
                px,
                topOuter.toArgb(),
                topInner.toArgb(),
                bottomOuter.toArgb(),
                bottomInner.toArgb(),
                paint,
            )

            if (active && enabled && windowFocused) {
                val focusInset = (if (default) 5.dp else 4.dp)
                    .toPx()
                    .roundToInt()
                    .coerceAtLeast((px * 2f).roundToInt())
                    .toFloat()
                native.dottedBorder(
                    focusInset,
                    focusInset,
                    right - focusInset,
                    bottom - focusInset,
                    px,
                    focusColor.toArgb(),
                    paint,
                )
            }
        }
    }
}

/**
 * A full-size push button for dialogs, using the classic bevel above.
 *
 * The touch target stays at least 48 dp tall, while the drawn cap is inset a
 * few dp, exactly like [AccessibleIconButton]. [default] marks the action Enter
 * would take; it draws the extra black ring and is used for OK, Apply, Close
 * and the other affirmative actions. Activation stays on release through
 * [Modifier.clickable]; the focus rectangle is left in place afterwards.
 */
@Composable
internal fun ClassicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    default: Boolean = false,
    minWidth: Dp = 64.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val contentColor = LocalContentColor.current

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = 48.dp)
            .semantics(mergeDescendants = true) {}
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp, vertical = 5.dp)
            .classicButton(
                interaction = interaction,
                enabled = enabled,
                active = focused || hovered,
                default = default,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) {
                contentColor
            } else {
                contentColor.copy(alpha = 0.38f)
            },
        ) {
            content()
        }
    }
}

/**
 * A sunken, straight-edged text field in the same classic style: shadow on the
 * top/left, white on the bottom/right, then a black/light pair one pixel in.
 * The interior is white in the light theme, like a real Windows 98 edit field.
 */
@Composable
internal fun Modifier.classicField(enabled: Boolean = true): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val face = when {
        !enabled -> scheme.surfaceVariant.copy(alpha = 0.45f)
        // Lift a black AMOLED surface just enough for the sunken edges and the
        // typed digits to stay visible inside the field.
        dark -> lerp(scheme.surface, Color.White, 0.05f)
        else -> Color.White
    }
    val white = if (dark) lerp(face, Color.White, 0.55f) else Color.White
    val light = if (dark) lerp(face, Color.White, 0.25f) else Color(0xFFDFDFDF)
    val shadow = if (dark) lerp(face, Color.Black, 0.45f) else Color(0xFF808080)
    val black = if (dark) lerp(face, Color.Black, 0.85f) else Color.Black

    return drawBehind {
        val px = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
        val right = size.width.roundToInt().toFloat()
        val bottom = size.height.roundToInt().toFloat()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val paint = Paint().apply {
                isAntiAlias = false
                style = Paint.Style.FILL
            }

            native.fill(0f, 0f, right, bottom, face.toArgb(), paint)
            native.bevel(
                0f,
                0f,
                right,
                bottom,
                px,
                shadow.toArgb(),
                black.toArgb(),
                white.toArgb(),
                light.toArgb(),
                paint,
            )
        }
    }
}

/** Paints one rectangle, skipping zero-sized edges at tiny sizes. */
private fun android.graphics.Canvas.fill(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Int,
    paint: Paint,
) {
    if (right <= left || bottom <= top) return
    paint.color = color
    drawRect(left, top, right, bottom, paint)
}

/** The 2x2 classic bevel: outer pair, then the inner pair one pixel in. */
private fun android.graphics.Canvas.bevel(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    px: Float,
    topOuter: Int,
    topInner: Int,
    bottomOuter: Int,
    bottomInner: Int,
    paint: Paint,
) {
    fill(left, top, right, top + px, topOuter, paint)
    fill(left, top + px, left + px, bottom, topOuter, paint)
    fill(left, bottom - px, right, bottom, bottomOuter, paint)
    fill(right - px, top + px, right, bottom - px, bottomOuter, paint)
    fill(left + px, top + px, right - px, top + px * 2f, topInner, paint)
    fill(left + px, top + px * 2f, left + px * 2f, bottom - px, topInner, paint)
    fill(left + px, bottom - px * 2f, right - px, bottom - px, bottomInner, paint)
    fill(right - px * 2f, top + px * 2f, right - px, bottom - px, bottomInner, paint)
}

/** A solid one-pixel ring, used for the default-button outline. */
private fun android.graphics.Canvas.ring(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    px: Float,
    color: Int,
    paint: Paint,
) {
    fill(left, top, right, top + px, color, paint)
    fill(left, bottom - px, right, bottom, color, paint)
    fill(left, top + px, left + px, bottom - px, color, paint)
    fill(right - px, top + px, right, bottom - px, color, paint)
}

/**
 * The keyboard focus cue: one-pixel square dots with one-pixel gaps. Drawing
 * the dots by hand, with anti-aliasing off, keeps them square and sharp; a
 * stroked path-effect rectangle would blur at fractional densities.
 */
private fun android.graphics.Canvas.dottedBorder(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    px: Float,
    color: Int,
    paint: Paint,
) {
    if (right - left < px * 2f || bottom - top < px * 2f) return
    paint.color = color
    var x = left
    while (x < right) {
        drawRect(x, top, minOf(x + px, right), top + px, paint)
        drawRect(x, bottom - px, minOf(x + px, right), bottom, paint)
        x += px * 2f
    }
    var y = top
    while (y < bottom) {
        drawRect(left, y, left + px, minOf(y + px, bottom), paint)
        drawRect(right - px, y, right, minOf(y + px, bottom), paint)
        y += px * 2f
    }
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
