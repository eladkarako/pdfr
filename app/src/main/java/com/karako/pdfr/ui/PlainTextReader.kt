package com.karako.pdfr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.sp
import com.karako.pdfr.R
import com.karako.pdfr.data.PlainText
import com.karako.pdfr.data.TextPage
import com.karako.pdfr.data.TextPagination
import kotlin.math.roundToInt

/**
 * The custom plain-text reader.
 *
 * MuPDF turns text into HTML pages, which cannot express line numbers, a
 * no-wrap horizontal mode, right-to-left alignment, or one continuous page.
 * This view renders the decoded lines directly. Pinch zoom and two-finger pan
 * are deliberately absent: text size and wrapping are explicit menu choices
 * instead, so the layout never has to repaginate under a gesture.
 *
 * The text sits inside a single [SelectionContainer], so selection can span
 * paragraphs rather than only one row. Line numbers are excluded with
 * [DisableSelection] and never end up on the clipboard. Manual selection is
 * only meant for a few lines: the lazy list composes just the rows near the
 * viewport, so a custom [TextToolbar] offers "Copy all" next to Copy. It
 * copies the full decoded document directly, whatever the current selection
 * is, and the overflow menu offers the same action. There is deliberately no
 * Select all: selecting everything only existed to then copy it.
 */
@Composable
internal fun PlainTextReader(
    text: String,
    textSizePt: Int,
    monospace: Boolean,
    singlePage: Boolean,
    lineWrap: Boolean,
    alignRight: Boolean,
    lineNumbers: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val lines = remember(text) { PlainText.lines(text) }
    val lineStyle = remember(textSizePt, monospace) {
        TextStyle(
            fontSize = ptToSp(textSizePt),
            lineHeight = ptToSp(textSizePt) * 1.4f,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
        )
    }
    val numberStyle = remember(textSizePt) {
        TextStyle(
            fontSize = ptToSp(textSizePt),
            lineHeight = ptToSp(textSizePt) * 1.4f,
            fontFamily = FontFamily.Monospace,
            color = Color.Gray,
        )
    }

    val direction = if (alignRight) LayoutDirection.Rtl else LayoutDirection.Ltr
    val alignment = if (alignRight) TextAlign.Right else TextAlign.Left
    val padding = 10.dp
    val context = LocalContext.current
    val selectionToolbar = remember { PlainTextTextToolbar() }
    val copyAll = {
        // Copies the decoded document, never the selection: the lazy list only
        // composes the rows near the viewport.
        copyToClipboard(context, PlainText.copyText(text), R.string.copied_all)
    }
    // The toolbar is drawn inside this window rather than in a Popup. A
    // focusable popup would make the selection manager re-show the toolbar as
    // soon as it closes and the reader regains focus.
    var readerBounds by remember { mutableStateOf(IntRect.Zero) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .ignoreMultiTouch()
            .onGloballyPositioned { readerBounds = it.boundsInRoot().roundToIntRect() },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.toInt()
        val heightPx = with(density) { maxHeight.toPx() }.toInt()
        val paddingPx = with(density) { padding.toPx() }.toInt()
        val contentHeightPx = (heightPx - paddingPx * 2).coerceAtLeast(1)

        val digits = PlainText.lineNumberDigits(lines.size)
        val gutterPx = if (lineNumbers) {
            measurer.measure(
                text = AnnotatedString("9".repeat(digits)),
                style = numberStyle,
            ).size.width + with(density) { 12.dp.toPx() }.toInt()
        } else {
            0
        }
        val gutterWidth: Dp = with(density) { gutterPx.toDp() }
        val textWidthPx = (widthPx - gutterPx - paddingPx * 2).coerceAtLeast(1)

        val pages = remember(
            lines,
            textWidthPx,
            contentHeightPx,
            lineStyle,
            lineWrap,
            singlePage,
        ) {
            if (!singlePage) {
                val singleLineHeight = if (lineWrap) {
                    0
                } else {
                    measurer.measure(
                        text = AnnotatedString("Mg"),
                        style = lineStyle,
                        softWrap = false,
                        maxLines = 1,
                    ).size.height.coerceAtLeast(1)
                }
                val heights = lines.map { line ->
                    if (lineWrap) {
                        measurer.measure(
                            text = AnnotatedString(line.ifEmpty { " " }),
                            style = lineStyle,
                            constraints = Constraints(maxWidth = textWidthPx),
                        ).size.height.coerceAtLeast(1)
                    } else {
                        singleLineHeight
                    }
                }
                TextPagination.paginate(heights, contentHeightPx)
            } else {
                emptyList()
            }
        }

        CompositionLocalProvider(LocalTextToolbar provides selectionToolbar) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                if (singlePage) {
                    SelectionContainer {
                        if (lineWrap) {
                            ContinuousWrappedLines(
                                lines = lines,
                                style = lineStyle,
                                numberStyle = numberStyle,
                                alignment = alignment,
                                gutterWidth = gutterWidth,
                                showLineNumbers = lineNumbers,
                                padding = padding,
                            )
                        } else {
                            ContinuousUnwrappedLines(
                                lines = lines,
                                style = lineStyle,
                                numberStyle = numberStyle,
                                alignment = alignment,
                                gutterWidth = gutterWidth,
                                showLineNumbers = lineNumbers,
                                padding = padding,
                            )
                        }
                    }
                } else {
                    PagedLines(
                        pages = pages,
                        lines = lines,
                        style = lineStyle,
                        numberStyle = numberStyle,
                        alignment = alignment,
                        gutterWidth = gutterWidth,
                        showLineNumbers = lineNumbers,
                        wrap = lineWrap,
                        padding = padding,
                    )
                }
            }
        }

        PlainTextSelectionToolbar(
            toolbar = selectionToolbar,
            readerBounds = readerBounds,
            onCopyAll = copyAll,
        )
    }
}

@Composable
private fun ContinuousWrappedLines(
    lines: List<String>,
    style: TextStyle,
    numberStyle: TextStyle,
    alignment: TextAlign,
    gutterWidth: Dp,
    showLineNumbers: Boolean,
    padding: Dp,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = padding),
    ) {
        items(count = lines.size) { index ->
            PlainTextLine(
                number = if (showLineNumbers) index + 1 else null,
                text = lines[index],
                style = style,
                numberStyle = numberStyle,
                alignment = alignment,
                gutterWidth = gutterWidth,
                wrap = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ContinuousUnwrappedLines(
    lines: List<String>,
    style: TextStyle,
    numberStyle: TextStyle,
    alignment: TextAlign,
    gutterWidth: Dp,
    showLineNumbers: Boolean,
    padding: Dp,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vertical)
            .padding(vertical = padding),
    ) {
        if (showLineNumbers) {
            DisableSelection {
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    lines.indices.forEach { index ->
                        Text(
                            text = (index + 1).toString(),
                            modifier = Modifier.width(gutterWidth),
                            style = numberStyle.copy(textAlign = alignment),
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.horizontalScroll(horizontal)) {
            Column {
                lines.forEach { line ->
                    Text(
                        text = line.ifEmpty { " " },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = style.copy(textAlign = alignment),
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

@Composable
private fun PagedLines(
    pages: List<TextPage>,
    lines: List<String>,
    style: TextStyle,
    numberStyle: TextStyle,
    alignment: TextAlign,
    gutterWidth: Dp,
    showLineNumbers: Boolean,
    wrap: Boolean,
    padding: Dp,
) {
    if (pages.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Box(modifier = Modifier.fillMaxSize()) {
        SelectionContainer {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                val page = pages[pageIndex]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (wrap) {
                        Column {
                            repeat(page.count) { offset ->
                                val index = page.start + offset
                                PlainTextLine(
                                    number = if (showLineNumbers) index + 1 else null,
                                    text = lines.getOrElse(index) { "" },
                                    style = style,
                                    numberStyle = numberStyle,
                                    alignment = alignment,
                                    gutterWidth = gutterWidth,
                                    wrap = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        Row {
                            if (showLineNumbers) {
                                DisableSelection {
                                    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                                        repeat(page.count) { offset ->
                                            val index = page.start + offset
                                            Text(
                                                text = (index + 1).toString(),
                                                modifier = Modifier.width(gutterWidth),
                                                style = numberStyle.copy(textAlign = alignment),
                                                softWrap = false,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                Column {
                                    repeat(page.count) { offset ->
                                        val index = page.start + offset
                                        Text(
                                            text = lines.getOrElse(index) { "" }.ifEmpty { " " },
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                            style = style.copy(textAlign = alignment),
                                            softWrap = false,
                                            maxLines = 1,
                                            overflow = TextOverflow.Visible,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        PageBadge(
            page = pagerState.currentPage + 1,
            total = pages.size,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun PlainTextLine(
    number: Int?,
    text: String,
    style: TextStyle,
    numberStyle: TextStyle,
    alignment: TextAlign,
    gutterWidth: Dp,
    wrap: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        if (number != null) {
            DisableSelection {
                Text(
                    text = number.toString(),
                    modifier = Modifier.width(gutterWidth),
                    style = numberStyle.copy(textAlign = alignment),
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = text.ifEmpty { " " },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            style = style.copy(textAlign = alignment),
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            overflow = if (wrap) TextOverflow.Clip else TextOverflow.Visible,
        )
    }
}

@Composable
private fun PageBadge(page: Int, total: Int, modifier: Modifier) {
    val alpha = remember { Animatable(PAGE_BADGE_START_ALPHA) }
    LaunchedEffect(page, total) {
        alpha.snapTo(PAGE_BADGE_START_ALPHA)
        alpha.animateTo(
            targetValue = PAGE_BADGE_END_ALPHA,
            animationSpec = tween(durationMillis = PAGE_BADGE_FADE_MS, easing = LinearEasing),
        )
    }
    Surface(
        modifier = modifier.alpha(alpha.value),
        shape = RoundedCornerShape(50),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Text(
            text = stringResource(R.string.page_of, page, total),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private val SelectionToolbarMargin = 8.dp

/**
 * The plain-text selection toolbar.
 *
 * Compose's default toolbar cannot add a custom action, and "Copy all" has to
 * work whatever is currently selected. This replacement keeps Copy and adds
 * Copy all. It deliberately drops Select all: selecting the whole document
 * only existed to then copy it. The reader is read-only, so paste and cut are
 * absent too.
 */
private class PlainTextTextToolbar : TextToolbar {

    var menu by mutableStateOf<Menu?>(null)
        private set

    override val status: TextToolbarStatus
        get() = if (menu != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        // Only Copy is needed; the other callbacks do not apply to read-only
        // text. In particular Select all is intentionally not offered.
        menu = Menu(rect = rect, onCopy = onCopyRequested)
    }

    override fun hide() {
        menu = null
    }

    data class Menu(
        val rect: Rect,
        val onCopy: (() -> Unit)?,
    )
}

@Composable
private fun PlainTextSelectionToolbar(
    toolbar: PlainTextTextToolbar,
    readerBounds: IntRect,
    onCopyAll: () -> Unit,
) {
    val menu = toolbar.menu ?: return
    val marginPx = with(LocalDensity.current) { SelectionToolbarMargin.roundToPx() }
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
    val offset = remember(menu.rect, readerBounds, marginPx, toolbarSize) {
        selectionToolbarOffset(menu.rect, readerBounds, marginPx, toolbarSize)
    }

    // BACK hides the menu before it can leave the reader. The selection
    // manager itself never consumes BACK.
    BackHandler { toolbar.hide() }

    // The reader may render the text right-to-left; the action order stays
    // left-to-right like the rest of the app's chrome.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = Modifier
                .offset { offset }
                .onSizeChanged { toolbarSize = it },
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Copy all never depends on the selection. Copy is greyed out
                // if the selection is empty.
                TextButton(
                    onClick = {
                        menu.onCopy?.invoke()
                        toolbar.hide()
                    },
                    enabled = menu.onCopy != null,
                ) {
                    Text(stringResource(R.string.copy))
                }
                TextButton(onClick = {
                    onCopyAll()
                    toolbar.hide()
                }) {
                    Text(stringResource(R.string.copy_all))
                }
            }
        }
    }
}

/**
 * Places the toolbar above the selection, below it when there is no room.
 * The selection rect is in root coordinates, so the reader's own bounds are
 * subtracted to get a position inside the reader.
 */
private fun selectionToolbarOffset(
    anchor: Rect,
    readerBounds: IntRect,
    marginPx: Int,
    toolbarSize: IntSize,
): IntOffset {
    val width = toolbarSize.width
    val height = toolbarSize.height
    if (width <= 0 || height <= 0) {
        return IntOffset(readerBounds.left, readerBounds.top)
    }

    val localLeft = anchor.left - readerBounds.left
    val localTop = anchor.top - readerBounds.top
    val localRight = anchor.right - readerBounds.left
    val localBottom = anchor.bottom - readerBounds.top

    val maxX = (readerBounds.width - width - marginPx).coerceAtLeast(marginPx)
    val x = ((localLeft + localRight) / 2f - width / 2f)
        .roundToInt()
        .coerceIn(marginPx, maxX)

    val above = localTop.roundToInt() - height - marginPx
    val below = localBottom.roundToInt() + marginPx
    val maxY = (readerBounds.height - height - marginPx).coerceAtLeast(marginPx)
    val y = if (above >= marginPx) {
        above
    } else {
        below.coerceIn(marginPx, maxY)
    }

    return IntOffset(x, y)
}

/** 1 pt is 1/72 inch; Android's scaled pixel is 1/160 inch at font scale 1. */
private fun ptToSp(points: Int): TextUnit = (points * (160f / 72f)).sp

/**
 * The plain-text reader has no pinch zoom or two-finger pan. Consume a second
 * pointer in the initial pass so the selection gestures underneath cannot read
 * a pinch as a long-press; otherwise a pinch would select a word and pop up the
 * selection toolbar instead of doing nothing.
 */
private fun Modifier.ignoreMultiTouch(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) {
                event.changes.forEach { it.consume() }
            }
        }
    }
}
