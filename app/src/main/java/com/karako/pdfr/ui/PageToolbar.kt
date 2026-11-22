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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karako.pdfr.R
import com.karako.pdfr.data.PageDirection

/**
 * Page position shared by the reader and its thin navigation toolbar.
 *
 * The reader publishes how many pages it is showing and which one occupies the
 * viewport; the toolbar edits the same values. Jumping is delegated back to the
 * reader, because only it knows whether that means a lazy list, a pager or a
 * single image.
 */
@Stable
internal class PageController {
    var pageCount by mutableIntStateOf(1)
        private set
    var currentPage by mutableIntStateOf(0)
        private set

    private var jump: ((Int) -> Unit)? = null

    fun setPages(count: Int, current: Int = currentPage) {
        val pages = count.coerceAtLeast(1)
        pageCount = pages
        currentPage = current.coerceIn(0, pages - 1)
    }

    fun updateCurrentPage(index: Int) {
        currentPage = index.coerceIn(0, pageCount - 1)
    }

    fun setJumpHandler(handler: ((Int) -> Unit)?) {
        jump = handler
    }

    fun goToPage(index: Int) {
        val target = index.coerceIn(0, pageCount - 1)
        currentPage = target
        jump?.invoke(target)
    }
}

/**
 * A permanent, thin page bar under the main toolbar: first, previous, an
 * editable page number with the total, next and last. It never fades and is
 * shown for every format the reader can open.
 */
@Composable
internal fun PageToolbar(
    controller: PageController,
    enabled: Boolean,
    direction: PageDirection,
    modifier: Modifier = Modifier,
) {
    val pageCount = controller.pageCount
    val current = controller.currentPage
    val hasPrevious = current > 0
    val hasNext = current < pageCount - 1

    // The bar follows the document language by default, so an RTL language
    // mirrors the whole row and its buttons. The main toolbar is wrapped in an
    // explicit LTR provider of its own and is unaffected. A stored LTR/RTL
    // choice overrides the language for this bar only.
    val layoutDirection = when (direction) {
        PageDirection.LANGUAGE -> LocalLayoutDirection.current
        PageDirection.LTR -> LayoutDirection.Ltr
        PageDirection.RTL -> LayoutDirection.Rtl
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            Column(modifier = modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PageButton(
                            label = "|<",
                            description = stringResource(R.string.page_first),
                            enabled = enabled && hasPrevious,
                        ) { controller.goToPage(0) }
                        PageButton(
                            label = "<",
                            description = stringResource(R.string.page_previous),
                            enabled = enabled && hasPrevious,
                        ) { controller.goToPage(current - 1) }
                    }

                    PageNumberBox(controller = controller, enabled = enabled)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PageButton(
                            label = ">",
                            description = stringResource(R.string.page_next),
                            enabled = enabled && hasNext,
                        ) { controller.goToPage(current + 1) }
                        PageButton(
                            label = ">|",
                            description = stringResource(R.string.page_last),
                            enabled = enabled && hasNext,
                        ) { controller.goToPage(pageCount - 1) }
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                )
            }
        }
    }
}

@Composable
private fun PageButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = enabled && (focused || hovered)
    val color = if (enabled) {
        LocalContentColor.current
    } else {
        LocalContentColor.current.copy(alpha = 0.38f)
    }
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = Modifier
            .width(40.dp)
            .height(30.dp)
            .clip(shape)
            .then(
                if (active) {
                    Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = description
            },
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun PageNumberBox(controller: PageController, enabled: Boolean) {
    val pageCount = controller.pageCount
    val current = controller.currentPage
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(6.dp)
    val currentLabel = stringResource(R.string.page_current)
    val totalLabel = stringResource(R.string.page_total)
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue((current + 1).toString())) }

    fun commit() {
        val page = text.text.toIntOrNull()
        if (page != null) controller.goToPage(page - 1)
        text = TextFieldValue((controller.currentPage + 1).toString())
    }

    // Scrolling, buttons and pager swipes all update the same field. The new
    // page number replaces whatever is in the field even while it is focused,
    // so typing can never hide the reader's real position.
    LaunchedEffect(current, pageCount) {
        text = TextFieldValue((current + 1).toString())
    }

    Row(
        modifier = Modifier
            .height(28.dp)
            .widthIn(min = 88.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { value ->
                val digits = value.text.filter(Char::isDigit).take(MAX_PAGE_DIGITS)
                text = if (digits == value.text) {
                    value
                } else {
                    value.copy(text = digits, selection = TextRange(digits.length))
                }
            },
            modifier = Modifier
                .width(42.dp)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        editing = true
                        // A tap starts a new page number instead of appending
                        // to the page that is already there.
                        text = text.copy(
                            selection = TextRange(0, text.text.length),
                        )
                    } else if (editing) {
                        editing = false
                        commit()
                    }
                }
                .semantics { contentDescription = currentLabel },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = LocalContentColor.current,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                textAlign = TextAlign.Left,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commit()
                    focusManager.clearFocus()
                },
            ),
        )
        PageJumpButton(enabled = enabled) {
            commit()
            focusManager.clearFocus()
        }
        Text(
            text = "/",
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
        )
        Text(
            text = pageCount.toString(),
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = totalLabel
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/**
 * The small button that commits the typed page number. It sits next to the
 * current-page field, so its side flips with the bar: right of the field in
 * LTR and left of it in RTL.
 */
@Composable
private fun PageJumpButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = stringResource(R.string.page_go),
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Drops keyboard focus on any press in the reader area.
 *
 * The initial pass runs before the content's own gesture handlers, so a tap
 * that scrolls, selects text or follows a link still clears the page field
 * without consuming the event. Taps inside the field itself never reach this
 * modifier because it is only attached to the page content.
 */
internal fun Modifier.clearFocusOnPress(focusManager: FocusManager): Modifier =
    pointerInput(focusManager) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) focusManager.clearFocus()
            }
        }
    }

private const val MAX_PAGE_DIGITS = 7
