package com.karako.pdfr.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karako.pdfr.R
import com.karako.pdfr.data.PdfDocumentInfo
import com.karako.pdfr.data.ShareSheet
import com.rajat.pdfviewer.PdfRendererView
import com.rajat.pdfviewer.compose.PdfRendererViewCompose
import com.rajat.pdfviewer.util.PdfSource
import java.io.File
import java.text.DateFormat
import java.util.Date

private const val PDF_MIME = "application/pdf"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderApp(
    state: ReaderState,
    documentInfo: PdfDocumentInfo?,
    viewModel: ReaderViewModel,
) {
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::open)
    }
    val openPicker = { picker.launch(arrayOf(PDF_MIME)) }

    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showPasswordDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is ReaderState.Locked) showPasswordDialog = true
        if (state !is ReaderState.Ready) showInfo = false
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
                        IconButton(onClick = { showInfo = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info),
                                contentDescription = stringResource(R.string.document_info),
                            )
                        }
                        IconButton(onClick = { ShareSheet.sharePdf(context, state.file) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = stringResource(R.string.share_pdf),
                            )
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
                    actionLabel = stringResource(R.string.open_pdf),
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

                is ReaderState.Ready -> PdfViewer(
                    file = state.file,
                    onError = viewModel::onRenderError,
                )
            }
        }
    }

    if (showInfo && state is ReaderState.Ready) {
        DocumentInfoDialog(
            file = state.file,
            displayName = state.displayName,
            info = documentInfo,
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
}

@Composable
private fun PdfViewer(file: File, onError: (Throwable) -> Unit) {
    var rendering by remember(file) { mutableStateOf(true) }

    // onPageChanged is 1-based while jumpToPage is 0-based. Remembering the
    // page keeps the reading position across rotation and process death.
    var lastPage by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }

    val currentOnError by rememberUpdatedState(onError)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Keying on the file gives each document a fresh PdfRendererView, which
        // avoids reusing a RecyclerView that is still bound to the old renderer.
        key(file.absolutePath) {
            PdfRendererViewCompose(
                source = remember(file) { PdfSource.LocalFile(file) },
                modifier = Modifier.fillMaxSize(),
                jumpToPage = lastPage.takeIf { it > 0 },
                statusCallBack = remember(file) {
                    object : PdfRendererView.StatusCallBack {
                        override fun onPdfRenderSuccess() {
                            rendering = false
                        }

                        override fun onPageChanged(currentPage: Int, totalPage: Int) {
                            lastPage = currentPage - 1
                        }

                        override fun onError(error: Throwable) {
                            rendering = false
                            currentOnError(error)
                        }
                    }
                },
            )
        }

        if (rendering) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun DocumentInfoDialog(
    file: File,
    displayName: String,
    info: PdfDocumentInfo?,
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
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                InfoRow(stringResource(R.string.file_name), displayName)
                InfoRow(
                    stringResource(R.string.file_size),
                    Formatter.formatFileSize(context, info?.sizeBytes ?: file.length()),
                )

                if (info == null) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                } else {
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
                            stringResource(R.string.password_protected_yes)
                        } else {
                            stringResource(R.string.no)
                        },
                    )

                    if (info.hasMetadata) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.pdf_metadata),
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
                        info.created?.let {
                            InfoRow(stringResource(R.string.metadata_created), formatDate(it))
                        }
                        info.modified?.let {
                            InfoRow(stringResource(R.string.metadata_modified), formatDate(it))
                        }
                    }
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

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

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
