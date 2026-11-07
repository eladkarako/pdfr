package com.karako.pdfr.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karako.pdfr.R
import com.karako.pdfr.data.DocumentFormats
import com.karako.pdfr.data.DocumentInfo
import com.karako.pdfr.data.PageBounds
import com.karako.pdfr.data.PageRanges
import com.karako.pdfr.data.RenderedPage
import com.karako.pdfr.data.ShareSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DEFAULT_PAGE_ASPECT = 1.4142f
private const val RENDER_SETTLE_MS = 120L
private const val CONTENT_WIDTH_SETTLE_MS = 200L
private const val ZOOM_ANIMATION_MS = 260

// A fixed blue, rather than a theme colour: reader pages are always white, so
// this stays visible even when the app's primary colour is very dark.
private val SELECTION_FILL = Color(0x592196F3)
private val SELECTION_OUTLINE = Color(0xB30D47A1)
private val SELECTION_HANDLE = Color(0xFF0D47A1)
private const val SELECTION_HANDLE_RADIUS_DP = 8f
private const val SELECTION_HANDLE_STEM_DP = 11f
private const val SELECTION_TOUCH_RADIUS_DP = 28f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderApp(
    state: ReaderState,
    selection: SelectionState?,
    viewModel: ReaderViewModel,
) {
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::open)
    }
    val openPicker = { picker.launch(DocumentFormats.PICKER_MIME_TYPES) }

    // ACTION_CREATE_DOCUMENT is used directly because the suggested MIME type
    // and filename change with the open document.
    val savePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let(viewModel::saveAs)
            }
        }

    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showReorderDialog by rememberSaveable { mutableStateOf(false) }
    var showRotateDialog by rememberSaveable { mutableStateOf(false) }
    var showLinearizeDialog by rememberSaveable { mutableStateOf(false) }
    var showCompactDialog by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(state) {
        when (state) {
            is ReaderState.Locked -> showPasswordDialog = true
            is ReaderState.Ready,
            ReaderState.Empty,
            is ReaderState.Error -> showPasswordDialog = false
            is ReaderState.Loading -> Unit
        }
        if (state !is ReaderState.Ready) {
            showInfo = false
            showReorderDialog = false
            showRotateDialog = false
            showLinearizeDialog = false
            showCompactDialog = false
        }
    }

    LaunchedEffect((state as? ReaderState.Ready)?.file?.absolutePath) {
        if (state is ReaderState.Ready) zoom = 1f
    }

    BackHandler(enabled = selection != null) {
        viewModel.clearSelection()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (state is ReaderState.Ready) {
                TopAppBar(
                    title = {
                        // Share on the left; info, open and save together in
                        // the middle; menu on the right. The app is forced to
                        // LTR so this order never flips in an RTL locale.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    ShareSheet.shareDocument(context, state.file, state.mimeType)
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_share),
                                    contentDescription = stringResource(R.string.share_document),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showInfo = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_info),
                                        contentDescription = stringResource(R.string.document_info),
                                    )
                                }
                                IconButton(
                                    onClick = openPicker,
                                    enabled = !busy,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_folder_open),
                                        contentDescription = stringResource(R.string.open_another),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = DocumentFormats.mimeTypeForName(state.displayName)
                                                ?: state.mimeType
                                            putExtra(Intent.EXTRA_TITLE, state.displayName)
                                        }
                                        savePicker.launch(intent)
                                    },
                                    enabled = !busy,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_save),
                                        contentDescription = stringResource(R.string.save_as),
                                    )
                                }
                            }
                            Box {
                                IconButton(
                                    onClick = { showOverflowMenu = true },
                                    enabled = !busy,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_more_vert),
                                        contentDescription = stringResource(R.string.more_options),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false },
                                ) {
                                    if (state.info.isPdf) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.reorder_pages)) },
                                            onClick = {
                                                showOverflowMenu = false
                                                showReorderDialog = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.rotate_pages)) },
                                            onClick = {
                                                showOverflowMenu = false
                                                showRotateDialog = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.linearize)) },
                                            onClick = {
                                                showOverflowMenu = false
                                                showLinearizeDialog = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.compact)) },
                                            onClick = {
                                                showOverflowMenu = false
                                                showCompactDialog = true
                                            },
                                        )
                                    }
                                    if (state.monospaceAvailable) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (state.monospace) {
                                                            R.string.monospace_on
                                                        } else {
                                                            R.string.monospace_off
                                                        },
                                                    ),
                                                )
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                viewModel.setMonospace(!state.monospace)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state) {
                ReaderState.Empty -> MessagePane(
                    iconRes = R.drawable.ic_document,
                    title = stringResource(R.string.empty_title),
                    body = stringResource(R.string.empty_body),
                    actionLabel = stringResource(R.string.open_document),
                    onAction = openPicker,
                )

                is ReaderState.Loading -> LoadingPane(name = state.name)

                is ReaderState.Error -> MessagePane(
                    iconRes = R.drawable.ic_document,
                    title = stringResource(R.string.error_title),
                    body = state.message,
                    actionLabel = stringResource(R.string.try_another),
                    onAction = openPicker,
                )

                is ReaderState.Locked -> MessagePane(
                    iconRes = R.drawable.ic_lock,
                    title = stringResource(R.string.password_required_title),
                    body = stringResource(R.string.password_required_body),
                    actionLabel = stringResource(R.string.enter_password),
                    onAction = { showPasswordDialog = true },
                )

                is ReaderState.Ready -> DocumentReader(
                    state = state,
                    selection = selection,
                    zoom = zoom,
                    onZoom = { zoom = it },
                    viewModel = viewModel,
                )
            }
        }
    }

    if (showInfo && state is ReaderState.Ready) {
        DocumentInfoDialog(
            info = state.info,
            onDismiss = { showInfo = false },
        )
    }

    val locked = state as? ReaderState.Locked
    if (locked != null && showPasswordDialog) {
        PasswordDialog(
            displayName = locked.displayName,
            wrongPassword = locked.wrongPassword,
            onDismiss = { showPasswordDialog = false },
            onUnlock = viewModel::unlock,
        )
    }

    if (showReorderDialog && state is ReaderState.Ready && state.info.isPdf) {
        ReorderPagesDialog(
            pageCount = state.pageCount,
            onDismiss = { showReorderDialog = false },
            onApply = { pages ->
                showReorderDialog = false
                // The dialog speaks in 1-based page numbers; MuPDF is 0-based.
                viewModel.reorderPages(IntArray(pages.size) { pages[it] - 1 })
            },
        )
    }

    if (showRotateDialog && state is ReaderState.Ready && state.info.isPdf) {
        RotatePagesDialog(
            pageCount = state.pageCount,
            onDismiss = { showRotateDialog = false },
            onApply = { pages ->
                showRotateDialog = false
                viewModel.rotatePages(IntArray(pages.size) { pages[it] - 1 })
            },
        )
    }

    if (showLinearizeDialog && state is ReaderState.Ready && state.info.isPdf) {
        LinearizeDialog(
            onDismiss = { showLinearizeDialog = false },
            onApply = {
                showLinearizeDialog = false
                viewModel.linearizePdf()
            },
        )
    }

    if (showCompactDialog && state is ReaderState.Ready && state.info.isPdf) {
        CompactDialog(
            onDismiss = { showCompactDialog = false },
            onApply = {
                showCompactDialog = false
                viewModel.compactPdf()
            },
        )
    }

    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.working)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.working_body))
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun ReorderPagesDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onApply: (IntArray) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val result = remember(query, pageCount) { PageRanges.parse(query, pageCount) }
    val pages = (result as? PageRanges.Result.Ok)?.pages
    val error = result as? PageRanges.Result.Error

    fun apply() {
        pages?.takeIf { it.isNotEmpty() }?.let(onApply)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reorder_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.reorder_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.reorder_hint)) },
                    isError = error != null && query.isNotBlank(),
                    supportingText = {
                        when {
                            query.isBlank() -> Text(stringResource(R.string.reorder_help))
                            error != null -> Text(pageRangeError(error, pageCount))
                            pages != null -> Text(
                                pluralStringResource(
                                    R.plurals.reorder_selected,
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
                enabled = pages != null && pages.isNotEmpty(),
            ) {
                Text(stringResource(R.string.reorder_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RotatePagesDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onApply: (IntArray) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("1-e") }
    val result = remember(query, pageCount) { PageRanges.parse(query, pageCount) }
    val pages = (result as? PageRanges.Result.Ok)?.pages
    val error = result as? PageRanges.Result.Error

    fun apply() {
        pages?.takeIf { it.isNotEmpty() }?.let(onApply)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rotate_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.rotate_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.rotate_hint)) },
                    isError = error != null && query.isNotBlank(),
                    supportingText = {
                        when {
                            error != null -> Text(pageRangeError(error, pageCount))
                            pages != null -> Text(
                                pluralStringResource(
                                    R.plurals.rotate_selected,
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
                enabled = pages != null && pages.isNotEmpty(),
            ) {
                Text(stringResource(R.string.rotate_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun pageRangeError(error: PageRanges.Result.Error, pageCount: Int): String =
    when (error.reason) {
        PageRanges.Reason.EMPTY -> stringResource(R.string.page_error_empty)
        PageRanges.Reason.EMPTY_ENTRY -> stringResource(R.string.page_error_empty_entry)
        PageRanges.Reason.BAD_TOKEN -> stringResource(
            R.string.page_error_token,
            error.token.orEmpty(),
        )
        PageRanges.Reason.OUT_OF_RANGE -> stringResource(
            R.string.page_error_range,
            error.page ?: 0,
            pageCount,
        )
        PageRanges.Reason.REVERSED -> stringResource(R.string.page_error_reversed)
        PageRanges.Reason.TOO_MANY -> stringResource(R.string.page_error_too_many)
    }

@Composable
private fun LinearizeDialog(
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    RewriteDialog(
        title = stringResource(R.string.linearize_title),
        body = stringResource(R.string.linearize_body),
        apply = stringResource(R.string.linearize_apply),
        onDismiss = onDismiss,
        onApply = onApply,
    )
}

@Composable
private fun CompactDialog(
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    RewriteDialog(
        title = stringResource(R.string.compact_title),
        body = stringResource(R.string.compact_body),
        apply = stringResource(R.string.compact_apply),
        onDismiss = onDismiss,
        onApply = onApply,
    )
}

@Composable
private fun RewriteDialog(
    title: String,
    body: String,
    apply: String,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.rewrite_replace_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onApply) {
                Text(apply)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun DocumentReader(
    state: ReaderState.Ready,
    selection: SelectionState?,
    zoom: Float,
    onZoom: (Float) -> Unit,
    viewModel: ReaderViewModel,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val densityDpi = context.resources.displayMetrics.densityDpi
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()
    val latestZoom by rememberUpdatedState(zoom)
    val scope = rememberCoroutineScope()
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    // Rendering and reflow follow the gesture with a short delay. Everything
    // is scaled from `zoom` immediately, so the page never waits for the
    // slower, sharper pass before changing size.
    var renderZoom by remember(state.file.absolutePath) { mutableFloatStateOf(1f) }
    LaunchedEffect(state.file.absolutePath, zoom) {
        if (state.reflowable) return@LaunchedEffect
        if (zoom == renderZoom) return@LaunchedEffect
        delay(RENDER_SETTLE_MS)
        renderZoom = zoom
    }

    // Reflowable pages are rendered at the viewport width for the last layout.
    // Keep the scroll rack wide enough for pages that have not re-rendered for
    // a newer layout yet, so they are not clipped while the new bitmaps land.
    var widthZoomFloor by remember(state.file.absolutePath) {
        mutableFloatStateOf(state.layoutZoom)
    }
    LaunchedEffect(state.file.absolutePath, state.layoutVersion) {
        widthZoomFloor = minOf(widthZoomFloor, state.layoutZoom)
        delay(CONTENT_WIDTH_SETTLE_MS)
        widthZoomFloor = state.layoutZoom
    }

    var pinchActive by remember { mutableStateOf(false) }
    var pinchStartZoom by remember { mutableFloatStateOf(1f) }
    var pinchStartScroll by remember { mutableIntStateOf(0) }
    var pinchStartFocus by remember { mutableStateOf(Offset.Zero) }
    var pinchFocus by remember { mutableStateOf(Offset.Zero) }

    // Page under the pinch centroid and the relative point inside it. As
    // pages grow or shrink, the list is scrolled so that point stays under
    // the fingers instead of drifting up towards the start of the document.
    // Moving both fingers pans the view while it is held.
    var pinchAnchorPage by remember { mutableIntStateOf(-1) }
    var pinchAnchorFraction by remember { mutableFloatStateOf(0f) }

    // Relative point inside the page that currently fills most of the
    // viewport, captured before a zoom reflow. It is the page the reader
    // bookmarks, so restoring the same fraction keeps the reading position
    // instead of snapping back to the top of that page.
    var restorePageFraction by remember(state.file.absolutePath) { mutableFloatStateOf(-1f) }

    fun primaryVisibleItem() = listState.layoutInfo.let { layout ->
        val start = layout.viewportStartOffset
        val end = layout.viewportEndOffset
        layout.visibleItemsInfo.maxByOrNull { item ->
            val overlapStart = maxOf(item.offset, start)
            val overlapEnd = minOf(item.offset + item.size, end)
            (overlapEnd - overlapStart).coerceAtLeast(0)
        }
    }

    fun visiblePageFraction(): Float {
        val item = primaryVisibleItem() ?: return 0f
        if (item.size <= 0) return 0f
        val top = maxOf(item.offset, listState.layoutInfo.viewportStartOffset)
        return ((top - item.offset).toFloat() / item.size).coerceIn(0f, 1f)
    }

    // Keep the remembered fraction in step with ordinary scrolling, so a
    // later reflow (rotation, window resize) restores the place the reader
    // actually stopped at, not the place they were at before scrolling.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) restorePageFraction = visiblePageFraction()
        }
    }

    // Keep the point under the fingers fixed while the content grows or
    // shrinks, both across the page and between pages. `pinchFocus` follows
    // the centroid, so moving both fingers pans the view at the same time.
    LaunchedEffect(zoom, pinchFocus, pinchActive) {
        if (!pinchActive) return@LaunchedEffect
        withFrameNanos { }
        val ratio = zoom / pinchStartZoom
        horizontalScroll.scrollTo(
            ZoomMath.anchoredScroll(
                startScroll = pinchStartScroll,
                startFocusX = pinchStartFocus.x,
                focusX = pinchFocus.x,
                ratio = ratio,
            ),
        )

        val anchor = pinchAnchorPage
        if (anchor >= 0) {
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor }
            if (item != null && item.size > 0) {
                val delta = ZoomMath.anchorDelta(
                    itemOffset = item.offset,
                    itemSize = item.size,
                    fraction = pinchAnchorFraction,
                    focusY = pinchFocus.y,
                )
                if (abs(delta) > 1f) {
                    listState.scrollBy(delta)
                }
            }
        }
    }

    // Double-tap glides between 100%, 200%, 400% and 600% instead of jumping.
    // Pinch zoom stays continuous and can stop on any value in that range.
    val doubleTapZoom = {
        val target = ZoomMath.nextDoubleTapZoom(latestZoom)
        zoomJob?.cancel()
        restorePageFraction = visiblePageFraction()
        zoomJob = scope.launch {
            animate(
                initialValue = latestZoom,
                targetValue = target,
                animationSpec = tween(
                    durationMillis = ZOOM_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            ) { value, _ -> onZoom(value) }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .twoFingerPinchZoom(
                minZoom = ZoomMath.MIN_ZOOM,
                maxZoom = ZoomMath.MAX_ZOOM,
                currentZoom = { latestZoom },
                onZoom = { value, focus ->
                    pinchFocus = focus
                    onZoom(value)
                },
                onZoomStart = { focus ->
                    zoomJob?.cancel()
                    pinchStartZoom = latestZoom
                    pinchStartScroll = horizontalScroll.value
                    pinchStartFocus = focus
                    pinchFocus = focus
                    pinchActive = true
                    restorePageFraction = visiblePageFraction()
                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                        focus.y >= info.offset && focus.y < info.offset + info.size
                    } ?: listState.layoutInfo.visibleItemsInfo.firstOrNull()
                    if (item != null && item.size > 0) {
                        pinchAnchorPage = item.index
                        pinchAnchorFraction = ((focus.y - item.offset) / item.size)
                            .coerceIn(0f, 1f)
                    } else {
                        pinchAnchorPage = -1
                    }
                },
                onZoomEnd = { pinchActive = false },
            ),
    ) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }.toInt()
        val viewportHeightPx = with(density) { maxHeight.toPx() }.toInt()

        // A pinch is scaled locally, so the expensive reflow only needs to
        // run once the fingers rest. Deferring it also avoids repaginating
        // halfway through the gesture, which would shift the text away from
        // the point being held.
        LaunchedEffect(viewportWidthPx, viewportHeightPx, densityDpi, zoom, pinchActive) {
            if (pinchActive) return@LaunchedEffect
            viewModel.onViewportChanged(viewportWidthPx, viewportHeightPx, densityDpi, zoom)
        }

        // Reflowable layouts only change when the new layout arrives; until
        // then the previous layout is scaled by zoom / layoutZoom. Fixed-layout
        // pages are rasterised wider and panned horizontally instead.
        val contentScale = if (state.reflowable) {
            ZoomMath.reflowRackScale(zoom, state.layoutZoom, widthZoomFloor)
        } else {
            zoom
        }
        val pageWidthPx = (viewportWidthPx * contentScale).toInt().coerceAtLeast(1)
        val pageWidthDp: Dp = with(density) { pageWidthPx.toDp() }

        LaunchedEffect(state.file.absolutePath) {
            horizontalScroll.scrollTo(0)
        }

        LaunchedEffect(state.file.absolutePath, state.layoutVersion, state.anchorPage) {
            if (state.pageCount <= 0) return@LaunchedEffect
            val page = state.anchorPage.coerceIn(0, state.pageCount - 1)
            val fraction = restorePageFraction
            listState.scrollToItem(page)
            if (fraction > 0f) {
                // Let the reflowed page be measured, then keep the same
                // relative point in it instead of snapping to its top. The
                // fraction stays remembered, so a reflow that lands while a
                // pinch is still running does not fall back to the page top.
                withFrameNanos { }
                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page }
                if (item != null && item.size > 0) {
                    listState.scrollToItem(page, (fraction * item.size).roundToInt())
                }
            }
        }

        // Report the page that occupies the largest part of the viewport, not
        // the first one that peeks in at the top. At the end of a scroll the
        // last page therefore wins as soon as it fills more than half the
        // screen instead of waiting for the previous page to leave entirely.
        val visiblePage by remember {
            derivedStateOf {
                primaryVisibleItem()?.index ?: listState.firstVisibleItemIndex
            }
        }
        LaunchedEffect(visiblePage) {
            viewModel.onPageChanged(visiblePage)
        }

        val contentPadding = if (state.reflowable) 8.dp else 16.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizontalScroll),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(pageWidthDp)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(vertical = contentPadding),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(
                    count = state.pageCount,
                    key = { index -> "${state.file.absolutePath}#$index" },
                ) { index ->
                    PageItem(
                        index = index,
                        documentKey = state.file.absolutePath,
                        viewportWidthPx = viewportWidthPx,
                        reflowable = state.reflowable,
                        liveZoom = zoom,
                        layoutZoom = state.layoutZoom,
                        renderZoom = renderZoom,
                        layoutVersion = state.layoutVersion,
                        selection = selection,
                        viewModel = viewModel,
                        onDoubleTap = doubleTapZoom,
                    )
                }
            }
        }

        if (selection != null) {
            SelectionBar(
                selection = selection,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                onCopy = { copySelection(context, selection.text) },
                onSelectAll = { viewModel.selectAll(selection.page) },
                onClose = viewModel::clearSelection,
            )
        } else if (state.pageCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                shape = RoundedCornerShape(50),
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Text(
                    text = stringResource(
                        R.string.page_of,
                        (visiblePage + 1).coerceAtMost(state.pageCount),
                        state.pageCount,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun PageItem(
    index: Int,
    documentKey: String,
    viewportWidthPx: Int,
    reflowable: Boolean,
    liveZoom: Float,
    layoutZoom: Float,
    renderZoom: Float,
    layoutVersion: Int,
    selection: SelectionState?,
    viewModel: ReaderViewModel,
    onDoubleTap: () -> Unit,
) {
    // Keep the last bitmap on screen while a sharper one is prepared. For
    // reflowable pages `renderedLayoutZoom` is the layout zoom of that bitmap,
    // so it can be stretched to the live zoom and swapped for the new layout
    // without the text changing size.
    var rendered by remember(documentKey, index) { mutableStateOf<RenderedPage?>(null) }
    var renderedLayoutZoom by remember(documentKey, index) {
        mutableFloatStateOf(layoutZoom)
    }

    val pageWidthPx = if (reflowable) {
        (viewportWidthPx *
            ZoomMath.reflowPageScale(liveZoom, renderedLayoutZoom))
            .roundToInt()
            .coerceAtLeast(1)
    } else {
        (viewportWidthPx * liveZoom).roundToInt().coerceAtLeast(1)
    }
    val renderWidthPx = if (reflowable) {
        // Reflowable pages always fill the viewport; their text gets larger
        // because the layout itself is narrower, not because the bitmap is.
        viewportWidthPx
    } else {
        (viewportWidthPx * renderZoom).roundToInt().coerceAtLeast(1)
    }

    LaunchedEffect(documentKey, index, layoutVersion, renderWidthPx) {
        val page = viewModel.renderPage(index, renderWidthPx) ?: return@LaunchedEffect
        rendered = page
        renderedLayoutZoom = layoutZoom
    }

    // Drop the page bitmap as soon as LazyColumn disposes this item; the LRU
    // cache still keeps it if it will be needed again, but off-screen items
    // should not pin large bitmaps in memory.
    DisposableEffect(documentKey, index) {
        onDispose { rendered = null }
    }

    val bounds = rendered?.bounds
    val aspect = bounds?.let { if (it.width > 0f) it.height / it.width else DEFAULT_PAGE_ASPECT }
        ?: DEFAULT_PAGE_ASPECT
    val density = LocalDensity.current
    val pageWidthDp: Dp = with(density) { pageWidthPx.toDp() }
    val pageHeightDp = with(density) { (pageWidthPx * aspect).toDp() }

    // A standard long-press detector still fires when a second finger joins
    // late in a pinch. Track the pointer count in the Initial pass and refuse
    // to start a text selection while more than one finger is down.
    val multiTouch = remember(documentKey, index) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .width(pageWidthDp)
            .height(pageHeightDp)
            .background(if (rendered == null) Color(0xFFF4F4F4) else Color.White)
            .pointerInput(documentKey, index) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        multiTouch.value = event.changes.count { it.pressed } > 1
                    }
                }
            }
            .then(
                if (bounds != null) {
                    Modifier
                        .pointerInput(documentKey, index) {
                            detectTapGestures(onDoubleTap = { onDoubleTap() })
                        }
                        .pointerInput(documentKey, index, bounds, pageWidthPx) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    if (!multiTouch.value) {
                                        bounds.toPagePoint(offset, pageWidthPx)?.let { point ->
                                            viewModel.startSelection(index, point.x, point.y)
                                        }
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (!multiTouch.value) {
                                        bounds.toPagePoint(change.position, pageWidthPx)?.let { point ->
                                            viewModel.updateSelection(index, point.x, point.y)
                                        }
                                    }
                                },
                                onDragEnd = { viewModel.finishSelection() },
                                onDragCancel = { viewModel.finishSelection() },
                            )
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        val page = rendered
        if (page != null) {
            Image(
                bitmap = page.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            if (selection != null && selection.page == index) {
                SelectionOverlay(
                    selection = selection,
                    bounds = page.bounds,
                    pageWidthPx = pageWidthPx,
                    onHandleStart = viewModel::beginSelectionHandle,
                    onHandleMove = viewModel::updateSelectionHandle,
                    onHandleEnd = viewModel::finishSelectionHandle,
                )
            }
        }
    }
}

/**
 * Draws the selected text and two draggable handles. The handles sit at the
 * first and last selected line; dragging one keeps the opposite end fixed, so
 * the range can still be corrected after the initial long-press has ended.
 */
@Composable
private fun SelectionOverlay(
    selection: SelectionState,
    bounds: PageBounds,
    pageWidthPx: Int,
    onHandleStart: (SelectionEdge) -> Unit,
    onHandleMove: (Float, Float) -> Unit,
    onHandleEnd: () -> Unit,
) {
    if (bounds.width <= 0f || selection.quads.isEmpty() || pageWidthPx <= 0) return

    val density = LocalDensity.current
    val radiusPx = with(density) { SELECTION_HANDLE_RADIUS_DP.dp.toPx() }
    val stemPx = with(density) { SELECTION_HANDLE_STEM_DP.dp.toPx() }
    val touchPx = with(density) { SELECTION_TOUCH_RADIUS_DP.dp.toPx() }
    val outlineWidth = with(density) { 1.5.dp.toPx() }
    val handleStroke = with(density) { 2.dp.toPx() }

    val scale = pageWidthPx.toFloat() / bounds.width
    fun pageToView(x: Float, y: Float) = Offset(
        (x - bounds.x0) * scale,
        (y - bounds.y0) * scale,
    )

    fun handleCenter(point: Offset, width: Float, height: Float): Offset = Offset(
        x = point.x.coerceIn(radiusPx, (width - radiusPx).coerceAtLeast(radiusPx)),
        y = (point.y + stemPx)
            .coerceIn(radiusPx, (height - radiusPx).coerceAtLeast(radiusPx)),
    )

    val first = selection.quads.first()
    val last = selection.quads.last()
    // Handles are drawn just below the first/last line, but the selection
    // endpoint is the middle of that line: a point on the bottom edge can be
    // read by MuPDF as belonging to the next line.
    val startAnchor = Offset(first.llX, (first.ulY + first.llY) / 2f)
    val endAnchor = Offset(last.lrX, (last.urY + last.lrY) / 2f)
    val startPoint = pageToView(first.llX, first.llY)
    val endPoint = pageToView(last.lrX, last.lrY)

    // The selection changes while a handle is dragged, but the pointer input
    // must not restart mid-gesture. Keep the hit-test points and anchors up to
    // date in separate state instead of keying the gesture on every quad.
    val startPointState = rememberUpdatedState(startPoint)
    val endPointState = rememberUpdatedState(endPoint)
    val startAnchorState = rememberUpdatedState(startAnchor)
    val endAnchorState = rememberUpdatedState(endAnchor)
    var activeEdge by remember(selection.page) { mutableStateOf<SelectionEdge?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(selection.page, bounds, pageWidthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startCenter = handleCenter(
                        startPointState.value,
                        size.width.toFloat(),
                        size.height.toFloat(),
                    )
                    val endCenter = handleCenter(
                        endPointState.value,
                        size.width.toFloat(),
                        size.height.toFloat(),
                    )
                    val startDistance = (down.position - startCenter).getDistance()
                    val endDistance = (down.position - endCenter).getDistance()
                    val edge = when {
                        startDistance <= touchPx && startDistance <= endDistance ->
                            SelectionEdge.Start
                        endDistance <= touchPx -> SelectionEdge.End
                        else -> null
                    } ?: return@awaitEachGesture

                    val downPage = Offset(
                        bounds.x0 + down.position.x / scale,
                        bounds.y0 + down.position.y / scale,
                    )
                    val anchor = when (edge) {
                        SelectionEdge.Start -> startAnchorState.value
                        SelectionEdge.End -> endAnchorState.value
                    }
                    // Keep the same relative grab point for the whole gesture,
                    // so the endpoint does not jump towards the finger when the
                    // handle sits below the line.
                    val grabOffset = downPage - anchor

                    down.consume()
                    activeEdge = edge
                    onHandleStart(edge)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes
                                .firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) break
                            change.consume()
                            val target = Offset(
                                bounds.x0 + change.position.x / scale,
                                bounds.y0 + change.position.y / scale,
                            ) - grabOffset
                            onHandleMove(target.x, target.y)
                        }
                    } finally {
                        activeEdge = null
                        onHandleEnd()
                    }
                }
            },
    ) {
        val path = Path()
        selection.quads.forEach { quad ->
            path.moveTo(quad.ulX * scale - bounds.x0 * scale, quad.ulY * scale - bounds.y0 * scale)
            path.lineTo(quad.urX * scale - bounds.x0 * scale, quad.urY * scale - bounds.y0 * scale)
            path.lineTo(quad.lrX * scale - bounds.x0 * scale, quad.lrY * scale - bounds.y0 * scale)
            path.lineTo(quad.llX * scale - bounds.x0 * scale, quad.llY * scale - bounds.y0 * scale)
            path.close()
        }
        drawPath(path, SELECTION_FILL)
        drawPath(path, SELECTION_OUTLINE, style = Stroke(width = outlineWidth))

        fun drawHandle(center: Offset, active: Boolean) {
            val radius = if (active) radiusPx * 1.25f else radiusPx
            drawLine(
                color = SELECTION_HANDLE,
                start = Offset(center.x, center.y - stemPx),
                end = center,
                strokeWidth = handleStroke,
                cap = StrokeCap.Round,
            )
            drawCircle(Color.White, radius = radius + handleStroke, center = center)
            drawCircle(SELECTION_HANDLE, radius = radius, center = center)
            drawCircle(
                Color.White.copy(alpha = 0.85f),
                radius = radius * 0.32f,
                center = center,
            )
        }

        drawHandle(
            handleCenter(startPoint, size.width, size.height),
            activeEdge == SelectionEdge.Start,
        )
        drawHandle(
            handleCenter(endPoint, size.width, size.height),
            activeEdge == SelectionEdge.End,
        )
    }
}

@Composable
private fun SelectionBar(
    selection: SelectionState,
    modifier: Modifier,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCopy,
                enabled = selection.text.isNotBlank(),
            ) {
                Text(stringResource(R.string.copy))
            }
            TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.select_all))
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

private fun copySelection(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

/**
 * Pinch-to-zoom that only reacts to two or more fingers. Single-finger drags
 * are left alone, so vertical scrolling, horizontal panning and long-press
 * selection keep working exactly as before.
 */
private fun Modifier.twoFingerPinchZoom(
    minZoom: Float,
    maxZoom: Float,
    currentZoom: () -> Float,
    onZoom: (Float, Offset) -> Unit,
    onZoomStart: (Offset) -> Unit = {},
    onZoomEnd: () -> Unit = {},
): Modifier = pointerInput(minZoom, maxZoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)

        var pinching = false
        var startDistance = 0f
        var startZoom = 1f

        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }

            if (pressed.size >= 2) {
                val first = pressed[0].position
                val second = pressed[1].position
                val distance = (first - second).getDistance()
                if (!pinching) {
                    if (distance > 8f) {
                        pinching = true
                        startDistance = distance
                        startZoom = currentZoom()
                        onZoomStart((first + second) / 2f)
                    }
                } else if (startDistance > 0f && distance > 0f) {
                    onZoom(
                        ZoomMath.pinchZoom(
                            startZoom = startZoom,
                            startDistance = startDistance,
                            distance = distance,
                            minZoom = minZoom,
                            maxZoom = maxZoom,
                        ),
                        (first + second) / 2f,
                    )
                    // Stop the scroll container from also moving under the pinch.
                    pressed.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })

        if (pinching) onZoomEnd()
    }
}

private fun PageBounds.toPagePoint(position: Offset, displayWidthPx: Int): Offset? {
    if (width <= 0f || displayWidthPx <= 0) return null
    val scale = displayWidthPx.toFloat() / width
    if (!scale.isFinite() || scale <= 0f) return null
    return Offset(
        x = x0 + position.x / scale,
        y = y0 + position.y / scale,
    )
}

@Composable
private fun DocumentInfoDialog(
    info: DocumentInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.document_info)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                InfoRow(stringResource(R.string.file_name), info.fileName)
                InfoRow(
                    stringResource(R.string.file_size),
                    Formatter.formatFileSize(context, info.sizeBytes),
                )
                InfoRow(stringResource(R.string.document_type), info.kind)
                info.format?.let {
                    if (!it.equals(info.kind, ignoreCase = true)) {
                        InfoRow(stringResource(R.string.document_format), it)
                    }
                }
                InfoRow(
                    stringResource(R.string.page_count),
                    pluralStringResource(
                        R.plurals.page_count_value,
                        info.pageCount,
                        info.pageCount,
                    ),
                )
                InfoRow(
                    stringResource(R.string.password_protected),
                    if (info.encrypted) {
                        stringResource(R.string.yes)
                    } else {
                        stringResource(R.string.no)
                    },
                )

                if (info.hasMetadata) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.document_metadata),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))

                    info.title?.let { InfoRow(stringResource(R.string.metadata_title), it) }
                    info.author?.let { InfoRow(stringResource(R.string.metadata_author), it) }
                    info.subject?.let { InfoRow(stringResource(R.string.metadata_subject), it) }
                    info.keywords?.let { InfoRow(stringResource(R.string.metadata_keywords), it) }
                    info.creator?.let { InfoRow(stringResource(R.string.metadata_creator), it) }
                    info.producer?.let { InfoRow(stringResource(R.string.metadata_producer), it) }
                    info.created?.let { InfoRow(stringResource(R.string.metadata_created), it) }
                    info.modified?.let { InfoRow(stringResource(R.string.metadata_modified), it) }
                }
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
private fun PasswordDialog(
    displayName: String,
    wrongPassword: Boolean,
    onDismiss: () -> Unit,
    onUnlock: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val submit = { if (password.isNotEmpty()) onUnlock(password) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.password_body, displayName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.password_label)) },
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text(stringResource(R.string.password_wrong)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LoadingPane(name: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = name ?: stringResource(R.string.loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessagePane(
    iconRes: Int,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}
