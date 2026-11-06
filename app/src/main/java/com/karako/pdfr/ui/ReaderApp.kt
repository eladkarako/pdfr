package com.karako.pdfr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karako.pdfr.R
import com.karako.pdfr.data.DocumentFormats
import com.karako.pdfr.data.DocumentInfo
import com.karako.pdfr.data.PageBounds
import com.karako.pdfr.data.PageRanges
import com.karako.pdfr.data.RenderedPage
import com.karako.pdfr.data.SelectionQuad
import com.karako.pdfr.data.ShareSheet
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ZOOM_OPTIONS = listOf(1f, 1.5f, 2f, 3f, 4f, 6f)
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f
private const val DEFAULT_PAGE_ASPECT = 1.4142f
private const val RENDER_SETTLE_MS = 120L

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

    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showReorderDialog by rememberSaveable { mutableStateOf(false) }
    var showRotateDialog by rememberSaveable { mutableStateOf(false) }
    var showLinearizeDialog by rememberSaveable { mutableStateOf(false) }
    var showCompactDialog by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var showZoomMenu by remember { mutableStateOf(false) }
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
            TopAppBar(
                title = {
                    Text(
                        text = when (state) {
                            is ReaderState.Ready -> state.displayName
                            is ReaderState.Locked -> state.displayName
                            is ReaderState.Loading -> state.name ?: stringResource(R.string.loading)
                            else -> stringResource(R.string.app_name)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    IconButton(onClick = openPicker) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder_open),
                            contentDescription = stringResource(R.string.open_another),
                        )
                    }
                    if (state is ReaderState.Ready) {
                        Box {
                            TextButton(onClick = { showZoomMenu = true }) {
                                Text("${(zoom * 100).roundToInt()}%")
                            }
                            DropdownMenu(
                                expanded = showZoomMenu,
                                onDismissRequest = { showZoomMenu = false },
                            ) {
                                ZOOM_OPTIONS.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("${(option * 100).roundToInt()}%") },
                                        onClick = {
                                            zoom = option
                                            showZoomMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { showInfo = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info),
                                contentDescription = stringResource(R.string.document_info),
                            )
                        }
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
            )
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

    val doubleTapZoom = {
        onZoom(
            when {
                zoom < 2f -> 2f
                zoom < 4f -> 4f
                zoom < MAX_ZOOM -> MAX_ZOOM
                else -> MIN_ZOOM
            },
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .twoFingerPinchZoom(
                minZoom = MIN_ZOOM,
                maxZoom = MAX_ZOOM,
                currentZoom = { latestZoom },
                onZoom = onZoom,
            ),
    ) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }.toInt()
        val viewportHeightPx = with(density) { maxHeight.toPx() }.toInt()

        LaunchedEffect(viewportWidthPx, viewportHeightPx, densityDpi, zoom) {
            viewModel.onViewportChanged(viewportWidthPx, viewportHeightPx, densityDpi, zoom)
        }

        // Reflowable formats lay their text out at the current zoom, so their
        // page keeps the viewport width. Fixed-layout pages are rasterised
        // wider and panned horizontally instead.
        val displayScale = if (state.reflowable) 1f else zoom
        val pageWidthPx = (viewportWidthPx * displayScale).toInt().coerceAtLeast(1)
        val pageWidthDp: Dp = with(density) { pageWidthPx.toDp() }

        LaunchedEffect(state.file.absolutePath, displayScale) {
            horizontalScroll.scrollTo(0)
        }

        LaunchedEffect(state.file.absolutePath, state.layoutVersion, state.anchorPage) {
            if (state.pageCount > 0) {
                listState.scrollToItem(state.anchorPage.coerceIn(0, state.pageCount - 1))
            }
        }

        val visiblePage by remember {
            derivedStateOf { listState.firstVisibleItemIndex }
        }
        LaunchedEffect(visiblePage) {
            viewModel.onPageChanged(visiblePage)
        }

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
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(
                    count = state.pageCount,
                    key = { index -> "${state.file.absolutePath}#$index" },
                ) { index ->
                    PageItem(
                        index = index,
                        documentKey = state.file.absolutePath,
                        pageWidthPx = pageWidthPx,
                        pageWidthDp = pageWidthDp,
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
    pageWidthPx: Int,
    pageWidthDp: Dp,
    layoutVersion: Int,
    selection: SelectionState?,
    viewModel: ReaderViewModel,
    onDoubleTap: () -> Unit,
) {
    // Rendering follows the pinch with a short delay. The page box still
    // resizes immediately, so the gesture feels live while the native renderer
    // only runs once the fingers rest.
    var renderWidth by remember(documentKey, index, layoutVersion) {
        mutableIntStateOf(pageWidthPx)
    }
    LaunchedEffect(documentKey, index, layoutVersion, pageWidthPx) {
        if (pageWidthPx != renderWidth) {
            delay(RENDER_SETTLE_MS)
            renderWidth = pageWidthPx
        }
    }

    var rendered by remember(documentKey, index, layoutVersion, renderWidth) {
        mutableStateOf<RenderedPage?>(null)
    }

    LaunchedEffect(documentKey, index, layoutVersion, renderWidth) {
        rendered = viewModel.renderPage(index, renderWidth)
    }

    // Drop the page bitmap as soon as LazyColumn disposes this item; the LRU
    // cache still keeps it if it will be needed again, but off-screen items
    // should not pin large bitmaps in memory.
    DisposableEffect(documentKey, index, layoutVersion, renderWidth) {
        onDispose { rendered = null }
    }

    val bounds = rendered?.bounds
    val aspect = bounds?.let { if (it.width > 0f) it.height / it.width else DEFAULT_PAGE_ASPECT }
        ?: DEFAULT_PAGE_ASPECT
    val pageHeightDp = with(LocalDensity.current) { (pageWidthPx * aspect).toDp() }

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
                SelectionHighlight(
                    quads = selection.quads,
                    bounds = page.bounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SelectionHighlight(
    quads: List<SelectionQuad>,
    bounds: PageBounds,
    modifier: Modifier,
) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)

    Canvas(modifier = modifier) {
        if (bounds.width <= 0f) return@Canvas
        val scale = size.width / bounds.width
        val offsetX = -bounds.x0 * scale
        val offsetY = -bounds.y0 * scale
        val path = Path()

        quads.forEach { quad ->
            path.moveTo(quad.ulX * scale + offsetX, quad.ulY * scale + offsetY)
            path.lineTo(quad.urX * scale + offsetX, quad.urY * scale + offsetY)
            path.lineTo(quad.lrX * scale + offsetX, quad.lrY * scale + offsetY)
            path.lineTo(quad.llX * scale + offsetX, quad.llY * scale + offsetY)
            path.close()
        }

        drawPath(path, color)
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
    onZoom: (Float) -> Unit,
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
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                if (!pinching) {
                    if (distance > 8f) {
                        pinching = true
                        startDistance = distance
                        startZoom = currentZoom()
                    }
                } else if (startDistance > 0f && distance > 0f) {
                    val scale = distance / startDistance
                    if (scale.isFinite()) {
                        onZoom((startZoom * scale).coerceIn(minZoom, maxZoom))
                    }
                    // Stop the scroll container from also moving under the pinch.
                    pressed.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
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
