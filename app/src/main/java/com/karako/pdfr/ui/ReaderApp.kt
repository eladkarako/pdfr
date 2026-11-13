package com.karako.pdfr.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
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
import com.karako.pdfr.data.NormalizeOptions
import com.karako.pdfr.data.PageBounds
import com.karako.pdfr.data.PageRanges
import com.karako.pdfr.data.PlainText
import com.karako.pdfr.data.RenderedPage
import com.karako.pdfr.data.ShareSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess

// Reader timings, layout limits and selection colours live in ReaderConfig.

/**
 * A pinch session as seen from the reader's layout.
 *
 * [startScroll]/[startFocus] describe the gesture at the moment it began; the
 * anchor is applied as a function of the current zoom and midpoint, so moving
 * both fingers pans while the same content point stays under them.
 */
private data class PinchAnchor(
    val id: Int,
    val startZoom: Float,
    val startScroll: Int,
    val startFocus: Offset,
    val page: Int,
    val fraction: Float,
)

/** Everything whose change requires the pinch anchor to be recomputed. */
private data class PinchSignal(
    val zoom: Float,
    val focus: Offset,
    val maxScroll: Int,
    val layoutVersion: Int,
    val anchorSize: Int,
)

/** Everything that wakes the page badge before it fades again. */
private data class BadgeSignal(
    val page: Int,
    val zoom: Float,
    val active: Boolean,
    val verticalOffset: Int,
    val horizontalOffset: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderApp(
    state: ReaderState,
    selection: SelectionState?,
    viewModel: ReaderViewModel,
) {
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    // Fix PDF can start from the empty screen: remember that the next document
    // should be repaired as soon as it has been opened.
    var pendingFix by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            pendingFix = false
        } else {
            viewModel.open(uri)
        }
    }
    val openPicker = { picker.launch(DocumentFormats.PICKER_MIME_TYPES) }
    val openPdfForFix = { picker.launch(arrayOf(DocumentFormats.PDF)) }

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
    var showNormalizeDialog by rememberSaveable { mutableStateOf(false) }
    var showTextSizeDialog by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val infoDraft by viewModel.infoDraft.collectAsStateWithLifecycle()

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
            showNormalizeDialog = false
            showTextSizeDialog = false
            viewModel.cancelInfoEdit()
        }
    }

    LaunchedEffect((state as? ReaderState.Ready)?.file?.absolutePath) {
        if (state is ReaderState.Ready) zoom = 1f
    }

    // After Fix PDF opened the picker, wait until the chosen document is ready
    // (including after a password prompt) and then repair it. Non-PDF files
    // are reported instead of being passed to qpdf.
    LaunchedEffect(state, pendingFix) {
        if (!pendingFix) return@LaunchedEffect
        when (state) {
            is ReaderState.Ready -> {
                pendingFix = false
                if (state.info.isPdf) {
                    viewModel.fixPdf()
                } else {
                    viewModel.reportFixNeedsPdf()
                }
            }

            // The empty state is where the picker was just launched from, so
            // it must not cancel the pending fix. A cancelled picker clears
            // the flag in its own callback instead.
            is ReaderState.Error -> pendingFix = false
            else -> Unit
        }
    }

    BackHandler(enabled = selection != null) {
        viewModel.clearSelection()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // The toolbar is always visible. Outside Ready every document
            // action is disabled, so the buttons still communicate where they
            // are without pretending there is a file to act on.
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
                                val ready = state as? ReaderState.Ready ?: return@IconButton
                                ShareSheet.shareDocument(
                                    context = context,
                                    file = ready.file,
                                    mimeType = ready.mimeType,
                                    displayName = ready.displayName,
                                    lastModified = ready.lastModified,
                                )
                            },
                            enabled = state is ReaderState.Ready && !busy,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = stringResource(R.string.share_document),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (state is ReaderState.Ready) {
                                        viewModel.beginInfoEdit()
                                        showInfo = true
                                    }
                                },
                                enabled = state is ReaderState.Ready && !busy,
                            ) {
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
                                    val ready = state as? ReaderState.Ready ?: return@IconButton
                                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = DocumentFormats.mimeTypeForName(ready.displayName)
                                            ?: ready.mimeType
                                        putExtra(Intent.EXTRA_TITLE, ready.displayName)
                                    }
                                    savePicker.launch(intent)
                                },
                                enabled = state is ReaderState.Ready && !busy,
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
                                // Exit is available from every state, so the
                                // menu stays reachable with no document, after
                                // an error and while a password is required.
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
                                modifier = Modifier.heightIn(
                                    max = screenHeightDp * ReaderConfig.DIALOG_MAX_HEIGHT_FRACTION,
                                ),
                            ) {
                                val ready = state as? ReaderState.Ready
                                val plainText = ready?.plainText
                                // Fix PDF stays available with no document:
                                // it opens the picker and repairs the file that
                                // is chosen. It never appears for non-PDFs.
                                val canFixPdf =
                                    (ready?.info?.isPdf == true) ||
                                        state is ReaderState.Empty
                                if (canFixPdf) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.fix_pdf)) },
                                        onClick = {
                                            showOverflowMenu = false
                                            if (ready != null) {
                                                viewModel.fixPdf()
                                            } else {
                                                pendingFix = true
                                                openPdfForFix()
                                            }
                                        },
                                    )
                                    if (ready != null) HorizontalDivider()
                                }
                                // Copy all is the only action here; everything
                                // below changes how the document is shown, so
                                // the divider keeps the two groups apart.
                                if (plainText != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.copy_all)) },
                                        onClick = {
                                            showOverflowMenu = false
                                            // Copy the decoded document, not a
                                            // selection: the lazy list only
                                            // composes the lines near the
                                            // viewport, so Select all would
                                            // miss everything above or below.
                                            copyToClipboard(
                                                context,
                                                PlainText.copyText(plainText),
                                                R.string.copied_all,
                                            )
                                        },
                                    )
                                    HorizontalDivider()
                                }
                                if (ready != null) {
                                    if (ready.info.isPdf) {
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
                                            text = { Text(stringResource(R.string.normalize)) },
                                            onClick = {
                                                showOverflowMenu = false
                                                showNormalizeDialog = true
                                            },
                                        )
                                    }
                                    if (ready.reflowable) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        R.string.text_size_menu,
                                                        ready.textSizePt,
                                                    ),
                                                )
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                showTextSizeDialog = true
                                            },
                                        )
                                    }
                                    if (ready.plainText != null) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (ready.singlePage) {
                                                            R.string.single_page_on
                                                        } else {
                                                            R.string.single_page_off
                                                        },
                                                    ),
                                                )
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                viewModel.setSinglePage(!ready.singlePage)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (ready.lineWrap) {
                                                            R.string.line_wrap_on
                                                        } else {
                                                            R.string.line_wrap_off
                                                        },
                                                    ),
                                                )
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                viewModel.setLineWrap(!ready.lineWrap)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (ready.alignRight) {
                                                            R.string.align_right_on
                                                        } else {
                                                            R.string.align_right_off
                                                        },
                                                    ),
                                                )
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                viewModel.setAlignRight(!ready.alignRight)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (ready.lineNumbers) {
                                                            R.string.line_numbers_on
                                                        } else {
                                                            R.string.line_numbers_off
                                                        },
                                                    ),
                                                )
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                viewModel.setLineNumbers(!ready.lineNumbers)
                                            },
                                        )
                                    }
                                    if (ready.monospaceAvailable) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (ready.monospace) {
                                                            R.string.monospace_on
                                                        } else {
                                                            R.string.monospace_off
                                                        },
                                                    ),
                                                )
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                viewModel.setMonospace(!ready.monospace)
                                            },
                                        )
                                    }
                                }

                                // Exit is always the last entry, kept apart
                                // from the document actions by a divider.
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.exit)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        // Remove the task first so the
                                        // launcher has nothing to resume, then
                                        // end the process. PDFr has no service,
                                        // but this also stops its reader, qpdf
                                        // and metadata threads and the native
                                        // contexts they own.
                                        context.findActivity()?.finishAndRemoveTask()
                                        exitProcess(0)
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
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

    LaunchedEffect(showInfo, state) {
        if (showInfo && state is ReaderState.Ready) {
            viewModel.beginInfoEdit()
        }
    }

    if (showInfo && state is ReaderState.Ready && infoDraft != null) {
        DocumentInfoDialog(
            info = state.info,
            draft = infoDraft!!,
            busy = busy,
            onDraftChange = viewModel::updateInfoDraft,
            onDismiss = { showInfo = false },
            onCancel = {
                viewModel.cancelInfoEdit()
                showInfo = false
            },
            onApply = {
                viewModel.applyInfoEdit()
                showInfo = false
            },
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

    if (showNormalizeDialog && state is ReaderState.Ready && state.info.isPdf) {
        NormalizeDialog(
            onDismiss = { showNormalizeDialog = false },
            onApply = { options ->
                showNormalizeDialog = false
                viewModel.normalizePdf(options)
            },
        )
    }

    if (showTextSizeDialog && state is ReaderState.Ready) {
        TextSizeDialog(
            current = state.textSizePt,
            onDismiss = { showTextSizeDialog = false },
            onApply = { points ->
                showTextSizeDialog = false
                viewModel.setTextSizePt(points)
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
private fun NormalizeDialog(
    onDismiss: () -> Unit,
    onApply: (NormalizeOptions) -> Unit,
) {
    var options by remember { mutableStateOf(NormalizeOptions()) }
    var qualityText by remember {
        mutableStateOf(NormalizeOptions.DEFAULT_JPEG_QUALITY.toString())
    }

    fun updateQuality(text: String) {
        qualityText = text.filter(Char::isDigit).take(3)
        qualityText.toIntOrNull()?.let { quality ->
            options = options.copy(jpegQuality = quality)
        }
    }

    val quality = qualityText.toIntOrNull()
    val qualityValid = quality != null &&
        quality in NormalizeOptions.MIN_JPEG_QUALITY..NormalizeOptions.MAX_JPEG_QUALITY
    val needsQuality = options.optimizeImages
    val canApply = options.hasWork && (!needsQuality || qualityValid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.normalize_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = dimensionResource(R.dimen.normalize_dialog_max_height))
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.normalize_body),
                    style = MaterialTheme.typography.bodyMedium,
                )

                NormalizeSection(stringResource(R.string.normalize_section_streams))
                NormalizeOption(
                    title = stringResource(R.string.normalize_compress_streams),
                    description = stringResource(R.string.normalize_compress_streams_desc),
                    checked = options.compressStreams,
                    onCheckedChange = { options = options.copy(compressStreams = it) },
                )
                NormalizeOption(
                    title = stringResource(R.string.normalize_decode_all),
                    description = stringResource(R.string.normalize_decode_all_desc),
                    checked = options.decodeAllStreams,
                    onCheckedChange = { options = options.copy(decodeAllStreams = it) },
                )
                NormalizeOption(
                    title = stringResource(R.string.normalize_recompress_flate),
                    description = stringResource(R.string.normalize_recompress_flate_desc),
                    checked = options.recompressFlate,
                    onCheckedChange = { options = options.copy(recompressFlate = it) },
                )
                NormalizeOption(
                    title = stringResource(R.string.normalize_compression_level),
                    description = stringResource(R.string.normalize_compression_level_desc),
                    checked = options.compressionLevel9,
                    onCheckedChange = { options = options.copy(compressionLevel9 = it) },
                )

                NormalizeSection(stringResource(R.string.normalize_section_images))
                NormalizeOption(
                    title = stringResource(R.string.normalize_optimize_images),
                    description = stringResource(R.string.normalize_optimize_images_desc),
                    checked = options.optimizeImages,
                    onCheckedChange = { options = options.copy(optimizeImages = it) },
                )
                OutlinedTextField(
                    value = qualityText,
                    onValueChange = ::updateQuality,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = options.optimizeImages,
                    singleLine = true,
                    label = { Text(stringResource(R.string.normalize_jpeg_quality)) },
                    isError = needsQuality && !qualityValid,
                    supportingText = {
                        Text(
                            if (needsQuality && !qualityValid) {
                                stringResource(R.string.normalize_quality_error)
                            } else {
                                stringResource(R.string.normalize_jpeg_quality_desc)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )

                NormalizeSection(stringResource(R.string.normalize_section_structure))
                NormalizeOption(
                    title = stringResource(R.string.normalize_newline),
                    description = stringResource(R.string.normalize_newline_desc),
                    checked = options.newlineBeforeEndstream,
                    onCheckedChange = { options = options.copy(newlineBeforeEndstream = it) },
                )
                NormalizeOption(
                    title = stringResource(R.string.normalize_remove_resources),
                    description = stringResource(R.string.normalize_remove_resources_desc),
                    checked = options.removeUnreferencedResources,
                    onCheckedChange = {
                        options = options.copy(removeUnreferencedResources = it)
                    },
                )
                NormalizeOption(
                    title = stringResource(R.string.normalize_externalize_images),
                    description = stringResource(R.string.normalize_externalize_images_desc),
                    checked = options.externalizeInlineImages,
                    onCheckedChange = {
                        options = options.copy(externalizeInlineImages = it)
                    },
                )

                NormalizeSection(stringResource(R.string.normalize_section_output))
                NormalizeOption(
                    title = stringResource(R.string.normalize_linearize),
                    description = stringResource(R.string.normalize_linearize_desc),
                    checked = options.linearize,
                    onCheckedChange = { options = options.copy(linearize = it) },
                )
                NormalizeOption(
                    title = stringResource(R.string.normalize_pdfa),
                    description = stringResource(R.string.normalize_pdfa_desc),
                    checked = options.pdfA,
                    onCheckedChange = { options = options.copy(pdfA = it) },
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
                onClick = { onApply(options) },
                enabled = canApply,
            ) {
                Text(stringResource(R.string.normalize_apply))
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
private fun NormalizeSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun NormalizeOption(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DocumentReader(
    state: ReaderState.Ready,
    selection: SelectionState?,
    zoom: Float,
    onZoom: (Float) -> Unit,
    viewModel: ReaderViewModel,
) {
    val plainText = state.plainText
    if (plainText != null) {
        PlainTextReader(
            text = plainText,
            textSizePt = state.textSizePt,
            monospace = state.monospace,
            singlePage = state.singlePage,
            lineWrap = state.lineWrap,
            alignRight = state.alignRight,
            lineNumbers = state.lineNumbers,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val densityDpi = context.resources.displayMetrics.densityDpi
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()
    // Reflowable documents change their text size through the menu; pinch and
    // double-tap zoom stay reserved for fixed-layout pages. Their viewport is
    // always laid out at 100%.
    val zoomable = !state.reflowable
    val interactiveZoom = if (zoomable) zoom else 1f
    val latestZoom by rememberUpdatedState(interactiveZoom)
    val scope = rememberCoroutineScope()
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    // Rendering and reflow follow the gesture with a short delay. Everything
    // is scaled from `zoom` immediately, so the page never waits for the
    // slower, sharper pass before changing size.
    var renderZoom by remember(state.file.absolutePath) { mutableFloatStateOf(1f) }
    LaunchedEffect(state.file.absolutePath, interactiveZoom) {
        if (state.reflowable) return@LaunchedEffect
        if (interactiveZoom == renderZoom) return@LaunchedEffect
        delay(ReaderConfig.RENDER_SETTLE_MS)
        renderZoom = interactiveZoom
    }

    // Reflowable pages are rendered at the viewport width for the last layout.
    // Keep the scroll rack wide enough for pages that have not re-rendered for
    // a newer layout yet, so they are not clipped while the new bitmaps land.
    var widthZoomFloor by remember(state.file.absolutePath) {
        mutableFloatStateOf(state.layoutZoom)
    }
    LaunchedEffect(state.file.absolutePath, state.layoutVersion) {
        widthZoomFloor = minOf(widthZoomFloor, state.layoutZoom)
        delay(ReaderConfig.CONTENT_WIDTH_SETTLE_MS)
        widthZoomFloor = state.layoutZoom
    }

    var pinchActive by remember { mutableStateOf(false) }
    var pinchAnchor by remember { mutableStateOf<PinchAnchor?>(null) }
    var pinchFocus by remember { mutableStateOf(Offset.Zero) }
    var pinchSessionId by remember { mutableIntStateOf(0) }

    // Layout version a pinch has already restored to. The reading-position
    // effect must not run again for that version after the anchor is
    // released, or it would scroll away from the point the pinch kept.
    var pinchLayoutVersion by remember { mutableIntStateOf(-1) }

    // The page badge is a quiet hint, not a permanent overlay: it wakes on any
    // navigation, scroll, zoom or pan and then fades to almost nothing.
    val badgeAlpha = remember { Animatable(ReaderConfig.PAGE_BADGE_START_ALPHA) }

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

    // The pinch's zoom anchor is applied inside BoxWithConstraints, where the
    // new page width is known; see `LaunchedEffect(anchor.id)` below.
    val latestLayoutVersion by rememberUpdatedState(state.layoutVersion)

    // Any page change, scroll, zoom or pan wakes the badge. `collectLatest`
    // restarts the fade timer while movement continues, so the badge stays
    // readable during a gesture and only fades once the reader stops.
    LaunchedEffect(listState, horizontalScroll) {
        snapshotFlow {
            BadgeSignal(
                page = primaryVisibleItem()?.index ?: listState.firstVisibleItemIndex,
                zoom = latestZoom,
                active = pinchActive || listState.isScrollInProgress ||
                        horizontalScroll.isScrollInProgress,
                verticalOffset = listState.firstVisibleItemScrollOffset,
                horizontalOffset = horizontalScroll.value,
            )
        }.collectLatest {
            badgeAlpha.snapTo(ReaderConfig.PAGE_BADGE_START_ALPHA)
            badgeAlpha.animateTo(
                targetValue = ReaderConfig.PAGE_BADGE_END_ALPHA,
                animationSpec = tween(
                    durationMillis = ReaderConfig.PAGE_BADGE_FADE_MS,
                    easing = LinearEasing,
                ),
            )
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
                    durationMillis = ReaderConfig.ZOOM_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            ) { value, _ -> onZoom(value) }
        }
    }

    val pinchModifier = if (zoomable) {
        Modifier.twoFingerPinchZoom(
            minZoom = ZoomMath.MIN_ZOOM,
            maxZoom = ZoomMath.MAX_ZOOM,
            currentZoom = { latestZoom },
            onZoom = { value, focus ->
                pinchFocus = focus
                onZoom(value)
            },
            onZoomStart = { focus ->
                zoomJob?.cancel()
                restorePageFraction = visiblePageFraction()
                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                    focus.y >= info.offset && focus.y < info.offset + info.size
                } ?: primaryVisibleItem()
                pinchFocus = focus
                pinchActive = true
                pinchAnchor = PinchAnchor(
                    id = ++pinchSessionId,
                    startZoom = latestZoom,
                    startScroll = horizontalScroll.value,
                    startFocus = focus,
                    page = item?.index ?: -1,
                    fraction = if (item != null && item.size > 0) {
                        ((focus.y - item.offset) / item.size)
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                )
            },
            onPan = { dx, dy ->
                // Move the content with the fingers immediately. Waiting
                // for the zoom layout to catch up made a two-finger drag
                // feel like it was stuck in treacle.
                horizontalScroll.dispatchRawDelta(-dx)
                listState.dispatchRawDelta(-dy)
            },
            onZoomEnd = { pinchActive = false },
        )
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(pinchModifier),
    ) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }.toInt()
        val viewportHeightPx = with(density) { maxHeight.toPx() }.toInt()

        // A pinch is scaled locally, so the expensive reflow only needs to
        // run once the fingers rest. Deferring it also avoids repaginating
        // halfway through the gesture, which would shift the text away from
        // the point being held.
        LaunchedEffect(
            viewportWidthPx,
            viewportHeightPx,
            densityDpi,
            interactiveZoom,
            pinchActive,
        ) {
            if (pinchActive) return@LaunchedEffect
            viewModel.onViewportChanged(
                viewportWidthPx,
                viewportHeightPx,
                densityDpi,
                interactiveZoom,
            )
        }

        // Reflowable layouts only change when the new layout arrives; until
        // then the previous layout is scaled by zoom / layoutZoom. Fixed-layout
        // pages are rasterised wider and panned horizontally instead.
        val contentScale = if (state.reflowable) {
            ZoomMath.reflowRackScale(interactiveZoom, state.layoutZoom, widthZoomFloor)
        } else {
            interactiveZoom
        }
        val pageWidthPx = (viewportWidthPx * contentScale).toInt().coerceAtLeast(1)
        val pageWidthDp: Dp = with(density) { pageWidthPx.toDp() }

        // A pinch changes the page size, but the scroll rack only grows after
        // the next measure. Compensate in two steps: these effects run whenever
        // the zoom, the midpoint or the measured layout changes, so a scroll
        // that was clamped before the new width landed is corrected as soon as
        // the layout catches up instead of anchoring to the page's left edge.
        suspend fun applyPinchAnchor(anchor: PinchAnchor) {
            val ratio = latestZoom / anchor.startZoom
            val target = ZoomMath.anchoredScroll(
                startScroll = anchor.startScroll,
                startFocusX = anchor.startFocus.x,
                focusX = pinchFocus.x,
                ratio = ratio,
            )
            val horizontalDelta = (target - horizontalScroll.value).toFloat()
            if (abs(horizontalDelta) >= 0.5f) {
                horizontalScroll.dispatchRawDelta(horizontalDelta)
            }

            if (anchor.page >= 0) {
                val item = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == anchor.page }
                if (item != null && item.size > 0) {
                    val verticalDelta = ZoomMath.anchorDelta(
                        itemOffset = item.offset,
                        itemSize = item.size,
                        fraction = anchor.fraction,
                        focusY = pinchFocus.y,
                    )
                    if (abs(verticalDelta) > 1f) {
                        listState.dispatchRawDelta(verticalDelta)
                    }
                }
            }
        }

        val activeAnchor = pinchAnchor
        if (activeAnchor != null) {
            LaunchedEffect(activeAnchor.id) {
                snapshotFlow {
                    PinchSignal(
                        zoom = latestZoom,
                        focus = pinchFocus,
                        maxScroll = horizontalScroll.maxValue,
                        layoutVersion = latestLayoutVersion,
                        anchorSize = if (activeAnchor.page >= 0) {
                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.index == activeAnchor.page }?.size ?: 0
                        } else {
                            0
                        },
                    )
                }.collect {
                    applyPinchAnchor(activeAnchor)
                }
            }

            // The deferred reflow lands after the fingers lift. Keep the
            // anchor through that layout change and the width-floor switch
            // that follows it (or a short fallback), then release it so
            // ordinary scrolling owns the position again.
            LaunchedEffect(activeAnchor.id, pinchActive) {
                if (pinchActive) return@LaunchedEffect
                val version = latestLayoutVersion
                withTimeoutOrNull(ReaderConfig.POST_PINCH_SETTLE_MS) {
                    snapshotFlow { latestLayoutVersion }.first { it != version }
                }
                // New bitmaps and the rack width both change after the version
                // bump. The anchor effect keeps correcting while this delay
                // runs, so the page never drifts during the hand-off.
                delay(ReaderConfig.POST_PINCH_RENDER_MS)
                withFrameNanos { }
                applyPinchAnchor(activeAnchor)
                pinchLayoutVersion = latestLayoutVersion
                restorePageFraction = visiblePageFraction()
                if (pinchAnchor?.id == activeAnchor.id) pinchAnchor = null
            }
        }

        LaunchedEffect(state.file.absolutePath) {
            horizontalScroll.scrollTo(0)
        }

        LaunchedEffect(state.file.absolutePath, state.layoutVersion, state.anchorPage) {
            // A pinch keeps its own anchor through the delayed reflow; the
            // restored reading fraction must not fight it, or run again for
            // the layout version the pinch already settled.
            if (pinchActive || pinchAnchor != null) return@LaunchedEffect
            if (pinchLayoutVersion == state.layoutVersion) return@LaunchedEffect
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
                        liveZoom = interactiveZoom,
                        layoutZoom = state.layoutZoom,
                        renderZoom = renderZoom,
                        layoutVersion = state.layoutVersion,
                        selection = selection,
                        viewModel = viewModel,
                        onDoubleTap = if (zoomable) doubleTapZoom else null,
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
                onCopy = {
                    copyToClipboard(context, selection.text)
                    viewModel.clearSelection()
                },
                onSelectAll = { viewModel.selectAll(selection.page) },
            )
        } else if (state.pageCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .alpha(badgeAlpha.value),
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
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
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
    onDoubleTap: (() -> Unit)?,
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
    val aspect = bounds?.let { if (it.width > 0f) it.height / it.width else ReaderConfig.DEFAULT_PAGE_ASPECT }
        ?: ReaderConfig.DEFAULT_PAGE_ASPECT
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
            .background(
                colorResource(
                    if (rendered == null) {
                        R.color.reader_page_placeholder
                    } else {
                        R.color.reader_page_background
                    },
                ),
            )
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
                    Modifier.pointerInput(documentKey, index) {
                        detectTapGestures(
                            onTap = { viewModel.clearSelection() },
                            onDoubleTap = onDoubleTap?.let { callback ->
                                { _: Offset -> callback() }
                            },
                            onLongPress = {
                                if (!multiTouch.value) {
                                    viewModel.showSelectionMenu(index)
                                }
                            },
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
                )
            }
        }
    }
}

/**
 * Draws the highlight for a page-wide selection. There are deliberately no
 * drag handles: a long-press offers "Select all", and tapping elsewhere
 * dismisses it.
 */
@Composable
private fun SelectionOverlay(
    selection: SelectionState,
    bounds: PageBounds,
    pageWidthPx: Int,
) {
    if (bounds.width <= 0f || selection.quads.isEmpty() || pageWidthPx <= 0) return

    val density = LocalDensity.current
    val outlineWidth = with(density) {
        dimensionResource(R.dimen.selection_outline_width).toPx()
    }
    val scale = pageWidthPx.toFloat() / bounds.width

    Canvas(modifier = Modifier.fillMaxSize()) {
        val path = Path()
        selection.quads.forEach { quad ->
            path.moveTo((quad.ulX - bounds.x0) * scale, (quad.ulY - bounds.y0) * scale)
            path.lineTo((quad.urX - bounds.x0) * scale, (quad.urY - bounds.y0) * scale)
            path.lineTo((quad.lrX - bounds.x0) * scale, (quad.lrY - bounds.y0) * scale)
            path.lineTo((quad.llX - bounds.x0) * scale, (quad.llY - bounds.y0) * scale)
            path.close()
        }
        drawPath(path, ReaderConfig.SELECTION_FILL)
        drawPath(path, ReaderConfig.SELECTION_OUTLINE, style = Stroke(width = outlineWidth))
    }
}

@Composable
private fun SelectionBar(
    selection: SelectionState,
    modifier: Modifier,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
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
            if (selection.hasText) {
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.copy))
                }
            } else {
                TextButton(onClick = onSelectAll) {
                    Text(stringResource(R.string.select_all))
                }
            }
        }
    }
}

/** Finds the Activity behind a Compose context, which may be wrapped. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

internal fun copyToClipboard(
    context: Context,
    text: String,
    messageRes: Int = R.string.copied,
) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
}

/**
 * Pinch-to-zoom that only reacts to two or more fingers. Single-finger drags
 * are left alone, so vertical scrolling, horizontal panning and long-press
 * menu gestures keep working exactly as before.
 *
 * The whole gesture is observed in the Initial pass so it can claim the touch
 * as soon as a second finger joins. Zoom is anchored on the midpoint between
 * the fingers, and [onPan] receives the midpoint movement so the reader can
 * move the content immediately instead of waiting for the zoom layout.
 */
private fun Modifier.twoFingerPinchZoom(
    minZoom: Float,
    maxZoom: Float,
    currentZoom: () -> Float,
    onZoom: (Float, Offset) -> Unit,
    onZoomStart: (Offset) -> Unit = {},
    onPan: (Float, Float) -> Unit = { _, _ -> },
    onZoomEnd: () -> Unit = {},
): Modifier = pointerInput(minZoom, maxZoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        var pinching = false
        var startDistance = 0f
        var startZoom = 1f
        var lastFocus: Offset? = null

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }

            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                val first = pressed[0].position
                val second = pressed[1].position
                val distance = (first - second).getDistance()
                val focus = (first + second) / 2f

                if (!pinching) {
                    if (distance > ReaderConfig.PINCH_START_DISTANCE) {
                        pinching = true
                        startDistance = distance
                        startZoom = currentZoom()
                        lastFocus = focus
                        onZoomStart(focus)
                    }
                } else {
                    if (startDistance > 0f && distance > 0f) {
                        onZoom(
                            ZoomMath.pinchZoom(
                                startZoom = startZoom,
                                startDistance = startDistance,
                                distance = distance,
                                minZoom = minZoom,
                                maxZoom = maxZoom,
                            ),
                            focus,
                        )
                    }
                    lastFocus?.let { previous ->
                        onPan(focus.x - previous.x, focus.y - previous.y)
                    }
                    lastFocus = focus
                }

                // The reader moves the content itself, so the scroll
                // containers must not also follow the same fingers.
                pressed.forEach { it.consume() }
            } else if (pinching) {
                // Lifting one finger ends the pinch. The remaining finger
                // must not turn into a scroll halfway through the gesture.
                break
            } else {
                lastFocus = null
            }
        }

        if (pinching) onZoomEnd()
    }
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
            modifier = Modifier.size(dimensionResource(R.dimen.empty_state_icon_size)),
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
