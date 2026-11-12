package com.karako.pdfr.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.karako.pdfr.R
import com.karako.pdfr.data.DocumentDates
import com.karako.pdfr.data.DocumentFormats
import com.karako.pdfr.data.DocumentInfo
import com.karako.pdfr.data.DocumentMetadataDraft
import com.karako.pdfr.data.DocumentPasswordRequiredException
import com.karako.pdfr.data.DocumentStore
import com.karako.pdfr.data.DocumentWrongPasswordException
import com.karako.pdfr.data.MetadataSidecar
import com.karako.pdfr.data.MuPdf
import com.karako.pdfr.data.MuPdfDocument
import com.karako.pdfr.data.NormalizeOptions
import com.karako.pdfr.data.PageBounds
import com.karako.pdfr.data.PlainText
import com.karako.pdfr.data.Qpdf
import com.karako.pdfr.data.RenderedPage
import com.karako.pdfr.data.SelectionQuad
import com.karako.pdfr.data.TextSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface ReaderState {
    data object Empty : ReaderState
    data class Loading(val name: String?) : ReaderState

    data class Ready(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val info: DocumentInfo,
        val pageCount: Int,
        val reflowable: Boolean,
        /** Source file timestamp in milliseconds, kept for Save as. */
        val lastModified: Long? = null,
        val monospace: Boolean = false,
        val monospaceAvailable: Boolean = false,
        /** Decoded text for the custom plain-text reader; null for other formats. */
        val plainText: String? = null,
        val textSizePt: Int = 12,
        val singlePage: Boolean = true,
        val lineWrap: Boolean = true,
        val alignRight: Boolean = false,
        val lineNumbers: Boolean = true,
        val layoutVersion: Int = 0,
        val anchorPage: Int = 0,
        /**
         * Zoom the current reflow layout was produced at. The UI keeps showing
         * the old layout scaled by `zoom / layoutZoom` until the new layout is
         * ready, so a pinch never snaps to a new size.
         */
        val layoutZoom: Float = 1f,
    ) : ReaderState

    data class Locked(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val wrongPassword: Boolean = false,
    ) : ReaderState

    data class Error(val message: String) : ReaderState
}

/**
 * The text menu for one page, in MuPDF page coordinates.
 *
 * A long-press only opens the menu: [quads] and [text] stay empty until the
 * reader chooses "Select all", so nothing is highlighted before that. Copying
 * uses the same [text] that is drawn.
 */
data class SelectionState(
    val page: Int,
    val quads: List<SelectionQuad>,
    val text: String,
) {
    val hasText: Boolean get() = text.isNotBlank() && quads.isNotEmpty()
}

class ReaderViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Empty)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _selection = MutableStateFlow<SelectionState?>(null)
    val selection: StateFlow<SelectionState?> = _selection.asStateFlow()

    /**
     * Edits made in the info dialog. Kept here rather than in the dialog so
     * dismissing and reopening shows the same draft. Apply writes it to the
     * cache file; Cancel discards it.
     */
    private val _infoDraft = MutableStateFlow<DocumentMetadataDraft?>(null)
    val infoDraft: StateFlow<DocumentMetadataDraft?> = _infoDraft.asStateFlow()

    /** True while a PDF is being rewritten and replaced. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Short user-facing notices (toast text), produced on the main thread. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var document: MuPdfDocument? = null
    private var openJob: Job? = null
    private var layoutJob: Job? = null
    private var selectionJob: Job? = null
    private var evictJob: Job? = null

    private var generation = 0
    private var viewport: Viewport? = null
    private var lastLayout: LayoutSpec? = null
    private var currentPage = 0

    /** Kept in memory only, so an encrypted PDF can be rewritten. */
    private var password: String? = null
    private var monospace = false

    // Text options are global: they stay in effect when the next document is
    // opened, and survive rotation/process death through SavedStateHandle.
    private var textSizePt = TextSettings.sizePtOrDefault(savedState.get(KEY_TEXT_SIZE))
    private var singlePage = savedState.get<Boolean>(KEY_SINGLE_PAGE) ?: true
    private var lineWrap = savedState.get<Boolean>(KEY_LINE_WRAP) ?: true
    private var alignRight = savedState.get<Boolean>(KEY_ALIGN_RIGHT) ?: false
    private var lineNumbers = savedState.get<Boolean>(KEY_LINE_NUMBERS) ?: true

    private data class Viewport(
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val zoom: Float,
    )

    private data class LayoutSpec(
        val widthPt: Float,
        val heightPt: Float,
        val em: Float,
    )

    private data class LayoutResult(
        val page: Int,
        val pageCount: Int,
        val info: DocumentInfo,
    )

    init {
        val savedPath = savedState.get<String>(KEY_PATH)
        val savedFile = savedPath?.let(::File)?.takeIf { it.isFile }

        viewModelScope.launch {
            DocumentStore.cleanup(getApplication(), keep = savedFile)
        }

        if (savedFile != null) {
            restore(savedFile)
        } else {
            _state.value = ReaderState.Empty
        }
    }

    fun open(uri: Uri) {
        val app = getApplication<Application>()

        // Sharing PDFr's own document back to PDFr would only re-import the
        // file that is already open. Leave the viewer page position untouched.
        val current = (_state.value as? ReaderState.Ready)?.file
        if (current != null && uri.authority == "${app.packageName}.fileprovider") {
            return
        }

        _selection.value = null
        _infoDraft.value = null
        password = null
        // Plain text is monospace by default; non-text documents reset this in
        // load(). Starting true makes the text override visible immediately.
        monospace = true

        val name = DocumentStore.displayName(app, uri)
        val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: DocumentFormats.mimeTypeForName(name.orEmpty())
            ?: "application/octet-stream"

        _state.value = ReaderState.Loading(name)

        openJob?.cancel()
        openJob = viewModelScope.launch {
            try {
                val (file, lastModified) = withContext(Dispatchers.IO) {
                    val modified = DocumentStore.sourceLastModified(app, uri)
                    val created = DocumentStore.sourceCreated(app, uri)
                    val imported = DocumentStore.import(app, uri, name, modified)
                    // Birth times cannot be assigned on Android, so keep the
                    // provider's best creation value beside the cached file.
                    if (created != null) {
                        MetadataSidecar.write(
                            imported,
                            mapOf(
                                DocumentMetadataDraft.KEY_FILE_CREATED to
                                    DocumentDates.formatIso(created),
                            ),
                        )
                    }
                    imported to modified
                }
                load(
                    file = file,
                    displayName = name ?: file.name,
                    mimeType = mime,
                    password = null,
                    startPage = 0,
                    lastModified = lastModified,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun unlock(password: String) {
        val locked = _state.value as? ReaderState.Locked ?: return
        if (password.isEmpty()) return

        _selection.value = null

        // Stay in the Locked state while authenticating. The password dialog
        // therefore remains on screen and can show an inline error instead of
        // closing and asking the reader to start over.
        openJob?.cancel()
        openJob = viewModelScope.launch {
            load(
                file = locked.file,
                displayName = locked.displayName,
                mimeType = locked.mimeType,
                password = password,
                startPage = savedState.get<Int>(KEY_PAGE) ?: 0,
                lastModified = savedState.get<Long>(KEY_MTIME)
                    ?: locked.file.lastModified().takeIf { it > 0L },
            )
        }
    }

    /**
     * The UI reports its usable page area here. For reflowable documents this
     * is also the layout size; for fixed-layout documents it only affects how
     * pages are rasterised.
     */
    fun onViewportChanged(widthPx: Int, heightPx: Int, densityDpi: Int, zoom: Float) {
        if (widthPx <= 0 || heightPx <= 0 || densityDpi <= 0) return

        val next = Viewport(
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = densityDpi,
            zoom = ZoomMath.clamp(zoom),
        )
        val previous = viewport
        if (next == previous) return
        viewport = next

        val doc = document ?: return
        if (doc.isReflowable) {
            // Plain text uses the custom Compose reader; re-laying it out in
            // MuPDF would only waste time and change pages nobody displays.
            if (!doc.isPlainText) scheduleReflow(doc, next)
        } else if (previous != null && previous.zoom != next.zoom) {
            // Pinch zoom changes the target width many times per second. Wait
            // for the gesture to settle before dropping the old bitmaps.
            evictJob?.cancel()
            evictJob = viewModelScope.launch {
                delay(BITMAP_EVICT_DEBOUNCE_MS)
                MuPdf.execute { doc.evictRenderedPages() }
            }
        }
    }

    fun onPageChanged(index: Int) {
        if (index < 0) return
        currentPage = index
        savedState[KEY_PAGE] = index
    }

    suspend fun renderPage(index: Int, widthPx: Int): RenderedPage? {
        val doc = document ?: return null
        return withContext(MuPdf.dispatcher) { doc.renderPage(index, widthPx) }
    }

    suspend fun pageBounds(index: Int): PageBounds? {
        val doc = document ?: return null
        return withContext(MuPdf.dispatcher) { doc.pageBounds(index) }
    }

    /**
     * Opens the page's text menu without selecting anything. Called on a
     * long-press: the reader decides whether to select the whole page.
     */
    fun showSelectionMenu(page: Int) {
        selectionJob?.cancel()
        selectionJob = null
        ++selectionVersion
        _selection.value = SelectionState(page = page, quads = emptyList(), text = "")
    }

    /**
     * Selects the complete text of [page]. MuPDF's block walker is used instead
     * of a corner-to-corner rectangle, so text that lives in separate blocks or
     * columns is included too.
     */
    fun selectAll(page: Int) {
        val doc = document ?: return
        selectionJob?.cancel()
        val version = ++selectionVersion

        // Keep the menu visible while the native selection is prepared; the
        // Copy button appears as soon as the highlight is published.
        _selection.value = SelectionState(page = page, quads = emptyList(), text = "")

        selectionJob = viewModelScope.launch {
            val result = withContext(MuPdf.dispatcher) { doc.selectAll(page) }
            if (version != selectionVersion) return@launch
            if (result == null ||
                result.text.isBlank() ||
                result.quads.isEmpty()
            ) {
                clearSelection()
            } else {
                _selection.value = SelectionState(
                    page = page,
                    quads = result.quads,
                    text = result.text,
                )
            }
        }
    }

    fun clearSelection() {
        selectionVersion++
        selectionJob?.cancel()
        selectionJob = null
        _selection.value = null
    }

    private var selectionVersion = 0

    private fun restore(file: File) {
        val name = savedState.get<String>(KEY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: file.name
        val mime = savedState.get<String>(KEY_MIME)
            ?: DocumentFormats.mimeTypeForName(name)
            ?: DocumentFormats.mimeTypeForFile(file)

        monospace = savedState.get<Boolean>(KEY_MONO) ?: true
        val lastModified = savedState.get<Long>(KEY_MTIME)
            ?: file.lastModified().takeIf { it > 0L }

        _state.value = ReaderState.Loading(name)
        openJob = viewModelScope.launch {
            load(
                file = file,
                displayName = name,
                mimeType = mime,
                password = null,
                startPage = savedState.get<Int>(KEY_PAGE) ?: 0,
                lastModified = lastModified,
            )
        }
    }

    /**
     * Opens [file] and, on success, makes it the current document.
     *
     * The replacement document is opened before the previous one is closed, so
     * a failed rewrite can leave the reader exactly where it was. Set
     * [preservePrevious] for those rewrite calls; normal "open another file"
     * failures still drop the old document.
     */
    private suspend fun load(
        file: File,
        displayName: String,
        mimeType: String,
        password: String?,
        startPage: Int,
        preservePrevious: Boolean = false,
        lastModified: Long? = null,
    ): Boolean {
        val version = ++generation

        // Non-PDF metadata lives in a sidecar beside the cache file. Read it
        // before opening so an edited display name and timestamp are used by
        // the reader, Save as and Share alike.
        val sidecar = withContext(Dispatchers.IO) { MetadataSidecar.read(file) }
        val effectiveName = sidecar
            ?.get(DocumentMetadataDraft.KEY_DISPLAY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: displayName
        val sidecarModified = if (
            sidecar?.containsKey(DocumentMetadataDraft.KEY_FILE_MODIFIED) == true
        ) {
            DocumentDates.parseIso(sidecar[DocumentMetadataDraft.KEY_FILE_MODIFIED])
        } else {
            null
        }
        val effectiveModified = sidecarModified ?: lastModified
        effectiveModified?.takeIf { it > 0L }?.let { file.setLastModified(it) }

        // The reader resets its zoom when the document changes, so the first
        // layout must be built for 100% as well, not for the previous file's
        // pinch level.
        viewport = viewport?.copy(zoom = ZoomMath.MIN_ZOOM)

        layoutJob?.cancel()
        selectionJob?.cancel()
        _selection.value = null

        try {
            val (opened, info, plainText) = withContext(MuPdf.dispatcher) {
                val doc = MuPdfDocument.open(file, effectiveName, password, mimeType)
                try {
                    var appliedLayout: LayoutSpec? = null
                    if (doc.isReflowable) {
                        doc.applyReaderStyle(monospace)
                        val spec = layoutSpec(viewport ?: defaultViewport())
                        if (doc.layout(spec.widthPt, spec.heightPt, spec.em)) {
                            appliedLayout = spec
                        }
                    }
                    val documentInfo = DocumentMetadataDraft.applySidecar(doc.info(), sidecar)
                    if (documentInfo.pageCount <= 0) {
                        throw IOException(
                            getApplication<Application>().getString(R.string.error_no_pages),
                        )
                    }
                    lastLayout = appliedLayout
                    val text = if (doc.isPlainText) PlainText.read(file) else null
                    Triple(doc, documentInfo, text)
                } catch (t: Throwable) {
                    runCatching { doc.close() }
                    throw t
                }
            }

            if (version != generation) {
                withContext(MuPdf.dispatcher) { opened.close() }
                return false
            }

            val pageCount = info.pageCount.coerceAtLeast(1)
            val page = startPage.coerceIn(0, pageCount - 1)

            val previous = document
            document = opened
            currentPage = page

            this.password = if (opened.needsPasswordToOpen) password else null
            monospace = opened.isPlainText && monospace

            savedState[KEY_PATH] = file.absolutePath
            savedState[KEY_NAME] = effectiveName
            savedState[KEY_MIME] = mimeType
            savedState[KEY_PAGE] = page
            savedState[KEY_MONO] = monospace
            savedState[KEY_MTIME] = effectiveModified
            savedState[KEY_TEXT_SIZE] = textSizePt
            savedState[KEY_SINGLE_PAGE] = singlePage
            savedState[KEY_LINE_WRAP] = lineWrap
            savedState[KEY_ALIGN_RIGHT] = alignRight
            savedState[KEY_LINE_NUMBERS] = lineNumbers

            _state.value = ReaderState.Ready(
                file = file,
                displayName = effectiveName,
                mimeType = mimeType,
                info = info.copy(fileName = effectiveName),
                pageCount = pageCount,
                reflowable = opened.isReflowable,
                lastModified = effectiveModified,
                monospace = monospace,
                monospaceAvailable = opened.isPlainText,
                plainText = plainText,
                textSizePt = textSizePt,
                singlePage = singlePage,
                lineWrap = lineWrap,
                alignRight = alignRight,
                lineNumbers = lineNumbers,
                anchorPage = page,
                layoutZoom = ZoomMath.clamp((viewport ?: defaultViewport()).zoom),
            )
            _infoDraft.value = null

            if (previous != null && previous !== opened) {
                MuPdf.execute { previous.close() }
            }
            return true
        } catch (e: DocumentPasswordRequiredException) {
            if (!preservePrevious) {
                discardPreviousDocument()
                lock(
                    file,
                    effectiveName,
                    mimeType,
                    wrongPassword = false,
                    lastModified = lastModified
                )
            }
            return false
        } catch (e: DocumentWrongPasswordException) {
            if (!preservePrevious) {
                discardPreviousDocument()
                lock(
                    file,
                    effectiveName,
                    mimeType,
                    wrongPassword = true,
                    lastModified = lastModified
                )
            }
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!preservePrevious) {
                discardPreviousDocument()
                fail(e)
            }
            return false
        }
    }

    private fun discardPreviousDocument() {
        val previous = document
        document = null
        if (previous != null) MuPdf.execute { previous.close() }
    }

    private fun scheduleReflow(
        doc: MuPdfDocument,
        viewport: Viewport,
        force: Boolean = false,
    ) {
        val spec = layoutSpec(viewport)
        if (!force && spec == lastLayout) return

        val layoutZoom = ZoomMath.clamp(viewport.zoom)
        layoutJob?.cancel()
        val version = generation

        layoutJob = viewModelScope.launch {
            delay(REFLOW_DEBOUNCE_MS)

            val result = withContext(MuPdf.dispatcher) {
                val mark = doc.makeBookmark(currentPage)
                doc.applyReaderStyle(monospace)
                if (!doc.layout(spec.widthPt, spec.heightPt, spec.em)) {
                    return@withContext null
                }
                lastLayout = spec
                val page = doc.pageForBookmark(mark)
                    ?: currentPage
                LayoutResult(
                    page = page,
                    pageCount = doc.countPages(),
                    info = DocumentMetadataDraft.applySidecar(
                        doc.info(),
                        MetadataSidecar.read(doc.file),
                    ),
                )
            } ?: return@launch

            if (version != generation) return@launch

            clearSelection()
            currentPage = result.page
            savedState[KEY_PAGE] = currentPage

            val ready = _state.value as? ReaderState.Ready ?: return@launch
            _state.value = ready.copy(
                info = result.info,
                pageCount = result.pageCount,
                layoutVersion = ready.layoutVersion + 1,
                anchorPage = result.page,
                layoutZoom = layoutZoom,
            )
        }
    }

    /** Turns the monospace override on or off for a plain-text document. */
    fun setMonospace(enabled: Boolean) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (!ready.monospaceAvailable || ready.monospace == enabled) return

        monospace = enabled
        savedState[KEY_MONO] = enabled
        _state.value = ready.copy(monospace = enabled)

        // The custom plain-text view re-renders from the new state. Only fall
        // back to a MuPDF reflow if the text could not be decoded.
        if (ready.plainText != null) return
        val doc = document ?: return
        scheduleReflow(doc, viewport ?: defaultViewport(), force = true)
    }

    /** Sets the reflowable text size. Plain text uses it directly. */
    fun setTextSizePt(points: Int) {
        val ready = _state.value as? ReaderState.Ready ?: return
        val value = TextSettings.clampSizePt(points)
        if (value == textSizePt) return

        textSizePt = value
        savedState[KEY_TEXT_SIZE] = value
        _state.value = ready.copy(textSizePt = value)

        if (!ready.reflowable || ready.plainText != null) return
        val doc = document ?: return
        scheduleReflow(doc, viewport ?: defaultViewport(), force = true)
    }

    fun setSinglePage(enabled: Boolean) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (ready.singlePage == enabled) return
        singlePage = enabled
        savedState[KEY_SINGLE_PAGE] = enabled
        _state.value = ready.copy(singlePage = enabled)
    }

    fun setLineWrap(enabled: Boolean) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (ready.lineWrap == enabled) return
        lineWrap = enabled
        savedState[KEY_LINE_WRAP] = enabled
        _state.value = ready.copy(lineWrap = enabled)
    }

    fun setAlignRight(enabled: Boolean) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (ready.alignRight == enabled) return
        alignRight = enabled
        savedState[KEY_ALIGN_RIGHT] = enabled
        _state.value = ready.copy(alignRight = enabled)
    }

    fun setLineNumbers(enabled: Boolean) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (ready.lineNumbers == enabled) return
        lineNumbers = enabled
        savedState[KEY_LINE_NUMBERS] = enabled
        _state.value = ready.copy(lineNumbers = enabled)
    }

    /**
     * Replaces the current PDF with the supplied zero-based pages. Page order
     * and repeats are preserved and pages that are not listed are removed.
     */
    fun reorderPages(pageIndices: IntArray) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (!ready.info.isPdf) return
        if (pageIndices.isEmpty()) return
        if (pageIndices.any { it < 0 || it >= ready.pageCount }) return

        rewrite(ready, pages = pageIndices.copyOf(), rotate = null, kind = RewriteKind.REORDER)
    }

    /** Rotates the supplied zero-based pages 180 degrees, keeping the rest. */
    fun rotatePages(pageIndices: IntArray) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (!ready.info.isPdf) return
        if (pageIndices.isEmpty()) return
        if (pageIndices.any { it < 0 || it >= ready.pageCount }) return

        rewrite(ready, pages = null, rotate = pageIndices.copyOf(), kind = RewriteKind.ROTATE)
    }

    /**
     * Runs qpdf's Normalize transformations on the current PDF.
     *
     * All selected options are applied in one qpdf pass, so the output is
     * never written and reopened between steps. PDF/A is the only extra
     * pass; it is folded into the same writer call by the native bridge.
     */
    fun normalizePdf(options: NormalizeOptions) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (!ready.info.isPdf) return
        if (!options.canApply) return

        rewrite(
            ready,
            pages = null,
            rotate = null,
            kind = RewriteKind.NORMALIZE,
            normalizeOptions = options,
        )
    }

    /**
     * Rewrites the current PDF through qpdf's QDF repair cycle, fixing stream
     * lengths and the cross-reference table.
     */
    fun fixPdf() {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (!ready.info.isPdf) return

        rewrite(ready, pages = null, rotate = null, kind = RewriteKind.FIX)
    }

    /** Explains why a file chosen for Fix PDF cannot be repaired. */
    fun reportFixNeedsPdf() {
        _messages.tryEmit(
            getApplication<Application>().getString(R.string.fix_pdf_only_pdf),
        )
    }

    /**
     * Starts (or resumes) an info edit. The draft is kept in the ViewModel so
     * closing and reopening the dialog does not lose unsaved field changes.
     */
    fun beginInfoEdit() {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (_infoDraft.value == null) {
            _infoDraft.value = DocumentMetadataDraft.from(ready.info, ready.lastModified)
        }
    }

    fun updateInfoDraft(draft: DocumentMetadataDraft) {
        _infoDraft.value = draft
    }

    fun cancelInfoEdit() {
        _infoDraft.value = null
    }

    /**
     * Writes the dialog's draft to the cached document and reloads it.
     *
     * PDFs get their Info dictionary and XMP stream rewritten (and are saved
     * without encryption, even if the source was encrypted). Other formats
     * store the values in a sidecar beside the cache file, because MuPDF
     * cannot rewrite EPUB, images or plain text in place. Either way the
     * display name and timestamps become the ones Save as and Share use.
     */
    fun applyInfoEdit() {
        val ready = _state.value as? ReaderState.Ready ?: return
        val draft = _infoDraft.value ?: return
        if (!draft.isValid || _busy.value) return

        val app = getApplication<Application>()
        val targetName = DocumentStore.safeFileName(draft.displayName)
        val modified = draft.fileModifiedMillis() ?: ready.lastModified
        // The edited extension is display-only. Sharing and Save as keep the
        // MIME type of the bytes that are actually in the cache file.
        val mime = ready.mimeType

        _busy.value = true
        clearSelection()

        viewModelScope.launch {
            var output: File? = null
            try {
                if (ready.info.isPdf) {
                    val target = withContext(Dispatchers.IO) {
                        DocumentStore.derivedFile(app, "metadata.pdf")
                    }
                    output = target
                    val metadataChanged = draft.documentChanged(ready.info)
                    val saved = withContext(MuPdf.dispatcher) {
                        MuPdfDocument.savePdfCopy(
                            source = ready.file,
                            password = password,
                            destination = target,
                            pageIndices = null,
                            options = MuPdfDocument.PDF_REWRITE_OPTIONS,
                            metadataUpdate = if (metadataChanged) draft.pdfUpdate() else null,
                        )
                    }
                    if (!saved) {
                        _messages.tryEmit(app.getString(R.string.metadata_failed))
                        return@launch
                    }

                    modified?.takeIf { it > 0L }?.let { target.setLastModified(it) }
                    // PDF metadata itself is in the bytes; the birth and
                    // modified times live beside the rewritten cache file.
                    withContext(Dispatchers.IO) {
                        MetadataSidecar.write(target, draft.fileTimestampValues())
                    }
                    val replaced = load(
                        file = target,
                        displayName = targetName,
                        mimeType = mime,
                        password = password,
                        startPage = currentPage,
                        preservePrevious = true,
                        lastModified = modified,
                    )
                    if (!replaced) {
                        _messages.tryEmit(app.getString(R.string.metadata_failed))
                        return@launch
                    }

                    output = null // the reader now owns the rewritten file
                    MuPdf.execute { DocumentStore.discard(ready.file) }
                } else {
                    withContext(Dispatchers.IO) {
                        MetadataSidecar.write(ready.file, draft.sidecarValues())
                        modified?.takeIf { it > 0L }?.let { ready.file.setLastModified(it) }
                    }
                    val replaced = load(
                        file = ready.file,
                        displayName = targetName,
                        mimeType = mime,
                        password = password,
                        startPage = currentPage,
                        preservePrevious = true,
                        lastModified = modified,
                    )
                    if (!replaced) {
                        _messages.tryEmit(app.getString(R.string.metadata_failed))
                        return@launch
                    }
                }

                _infoDraft.value = null
                _messages.tryEmit(app.getString(R.string.metadata_applied))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.tryEmit(app.getString(R.string.metadata_failed))
            } finally {
                output?.delete()
                _busy.value = false
            }
        }
    }

    /**
     * Copies the document that is currently on screen to a user-chosen URI.
     * The bytes are copied verbatim, so the existing format (including any
     * reorder/rotate/normalize result and its embedded metadata) is
     * preserved. File timestamp and display name are carried over where the
     * target provider supports it.
     */
    fun saveAs(target: Uri) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (_busy.value) return

        val app = getApplication<Application>()
        _busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(target, "wt")
                        ?: throw IOException("The selected location is not writable.")
                    output.use { sink ->
                        ready.file.inputStream().use { source -> source.copyTo(sink) }
                    }
                    DocumentStore.applyLastModified(app, target, ready.lastModified)
                }
                val savedName = DocumentStore.displayName(app, target) ?: ready.displayName
                _messages.tryEmit(app.getString(R.string.saved_as, savedName))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.tryEmit(app.getString(R.string.save_failed))
            } finally {
                _busy.value = false
            }
        }
    }

    private fun rewrite(
        ready: ReaderState.Ready,
        pages: IntArray?,
        rotate: IntArray?,
        kind: RewriteKind,
        normalizeOptions: NormalizeOptions? = null,
    ) {
        if (_busy.value) return

        val app = getApplication<Application>()
        _busy.value = true
        clearSelection()

        viewModelScope.launch {
            var output: File? = null
            try {
                var failure: String? = null
                output = withContext(
                    if (kind == RewriteKind.NORMALIZE || kind == RewriteKind.FIX) {
                        Qpdf.dispatcher
                    } else {
                        MuPdf.dispatcher
                    },
                ) {
                    val target =
                        DocumentStore.derivedFile(app, derivedDisplayName(ready.displayName, kind))
                    val saved = when (kind) {
                        RewriteKind.NORMALIZE -> {
                            val options = normalizeOptions
                            if (options == null) {
                                false
                            } else {
                                val error = Qpdf.normalize(
                                    source = ready.file,
                                    destination = target,
                                    password = password,
                                    options = options,
                                )
                                failure = error
                                error == null
                            }
                        }

                        RewriteKind.FIX -> {
                            val error = Qpdf.fix(
                                source = ready.file,
                                destination = target,
                                password = password,
                            )
                            failure = error
                            error == null
                        }

                        else -> MuPdfDocument.savePdfCopy(
                            source = ready.file,
                            password = password,
                            destination = target,
                            pageIndices = pages,
                            rotatePages = rotate,
                            options = MuPdfDocument.PDF_REWRITE_OPTIONS,
                        )
                    }
                    if (saved) {
                        ready.lastModified?.takeIf { it > 0L }
                            ?.let { target.setLastModified(it) }
                        target
                    } else {
                        target.delete()
                        null
                    }
                }

                val created = output
                if (created == null) {
                    val message = when {
                        kind == RewriteKind.NORMALIZE &&
                                failure?.contains("not available") == true ->
                            R.string.normalize_unavailable

                        kind == RewriteKind.FIX &&
                                failure?.contains("not available") == true ->
                            R.string.fix_pdf_unavailable

                        else -> R.string.rewrite_failed
                    }
                    _messages.tryEmit(app.getString(message))
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val createdIso = ready.info.fileCreated
                        ?.takeIf { it > 0L }
                        ?.let { DocumentDates.formatIso(it) }
                    if (createdIso != null) {
                        MetadataSidecar.write(
                            created,
                            mapOf(DocumentMetadataDraft.KEY_FILE_CREATED to createdIso),
                        )
                    }
                }

                val replaced = load(
                    file = created,
                    displayName = derivedDisplayName(ready.displayName, kind),
                    mimeType = DocumentFormats.PDF,
                    password = password,
                    startPage = 0,
                    preservePrevious = true,
                    lastModified = ready.lastModified,
                )

                if (replaced) {
                    output = null // the reader now owns the file
                    // Close the old document before unlinking its cache file.
                    MuPdf.execute { DocumentStore.discard(ready.file) }
                    _messages.tryEmit(app.getString(kind.doneMessage))
                } else {
                    created.delete()
                    output = null
                    _messages.tryEmit(app.getString(R.string.rewrite_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.tryEmit(app.getString(R.string.rewrite_failed))
            } finally {
                output?.delete()
                _busy.value = false
            }
        }
    }

    private fun derivedDisplayName(original: String, kind: RewriteKind): String {
        val dot = original.lastIndexOf('.')
        val stem = if (dot > 0) original.substring(0, dot) else original
        return "$stem (${kind.label}).pdf"
    }

    private enum class RewriteKind(val label: String, val doneMessage: Int) {
        REORDER("reordered", R.string.reorder_done),
        ROTATE("rotated", R.string.rotate_done),
        NORMALIZE("normalized", R.string.normalize_done),
        FIX("fixed", R.string.fix_pdf_done),
    }

    private fun layoutSpec(viewport: Viewport): LayoutSpec {
        val pointsPerPixel = 72f / viewport.densityDpi
        val zoom = ZoomMath.clamp(viewport.zoom)
        return LayoutSpec(
            widthPt = (viewport.widthPx * pointsPerPixel / zoom).coerceAtLeast(32f),
            heightPt = (viewport.heightPx * pointsPerPixel / zoom).coerceAtLeast(32f),
            em = textSizePt.toFloat(),
        )
    }

    private fun defaultViewport(): Viewport {
        val metrics = getApplication<Application>().resources.displayMetrics
        return Viewport(
            widthPx = metrics.widthPixels.coerceAtLeast(200),
            heightPx = metrics.heightPixels.coerceAtLeast(300),
            densityDpi = metrics.densityDpi,
            zoom = 1f,
        )
    }

    private fun lock(
        file: File,
        displayName: String,
        mimeType: String,
        wrongPassword: Boolean,
        lastModified: Long?,
    ) {
        savedState[KEY_PATH] = file.absolutePath
        savedState[KEY_NAME] = displayName
        savedState[KEY_MIME] = mimeType
        savedState[KEY_MTIME] = lastModified

        _selection.value = null
        _state.value = ReaderState.Locked(
            file = file,
            displayName = displayName,
            mimeType = mimeType,
            wrongPassword = wrongPassword,
        )
    }

    private fun fail(error: Exception) {
        _selection.value = null
        _state.value = ReaderState.Error(
            error.message?.takeIf { it.isNotBlank() }
                ?: getApplication<Application>().getString(R.string.error_generic),
        )
    }

    override fun onCleared() {
        openJob?.cancel()
        layoutJob?.cancel()
        selectionJob?.cancel()
        evictJob?.cancel()

        val closing = document
        document = null
        if (closing != null) {
            MuPdf.execute { closing.close() }
        }
        super.onCleared()
    }

    internal companion object {
        const val KEY_PATH = "document_path"
        const val KEY_NAME = "document_name"
        const val KEY_MIME = "document_mime"
        const val KEY_PAGE = "document_page"
        const val KEY_MONO = "document_monospace"
        const val KEY_MTIME = "document_modified"
        const val KEY_TEXT_SIZE = "reader_text_size_pt"
        const val KEY_SINGLE_PAGE = "reader_single_page"
        const val KEY_LINE_WRAP = "reader_line_wrap"
        const val KEY_ALIGN_RIGHT = "reader_align_right"
        const val KEY_LINE_NUMBERS = "reader_line_numbers"

        const val REFLOW_DEBOUNCE_MS = 80L
        const val BITMAP_EVICT_DEBOUNCE_MS = 240L
    }
}
