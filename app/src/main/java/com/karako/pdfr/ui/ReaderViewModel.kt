package com.karako.pdfr.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.karako.pdfr.R
import com.karako.pdfr.data.DocumentFormats
import com.karako.pdfr.data.DocumentInfo
import com.karako.pdfr.data.DocumentPasswordRequiredException
import com.karako.pdfr.data.DocumentStore
import com.karako.pdfr.data.DocumentWrongPasswordException
import com.karako.pdfr.data.MuPdf
import com.karako.pdfr.data.MuPdfDocument
import com.karako.pdfr.data.PageBounds
import com.karako.pdfr.data.Qpdf
import com.karako.pdfr.data.RenderedPage
import com.karako.pdfr.data.SelectionQuad
import com.karako.pdfr.data.TextSelection
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
        val monospace: Boolean = false,
        val monospaceAvailable: Boolean = false,
        val layoutVersion: Int = 0,
        val anchorPage: Int = 0,
    ) : ReaderState

    data class Locked(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val wrongPassword: Boolean = false,
    ) : ReaderState

    data class Error(val message: String) : ReaderState
}

/** A selection inside one page, in MuPDF page coordinates. */
data class SelectionState(
    val page: Int,
    val quads: List<SelectionQuad>,
    val text: String,
    val dragging: Boolean,
)

class ReaderViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Empty)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _selection = MutableStateFlow<SelectionState?>(null)
    val selection: StateFlow<SelectionState?> = _selection.asStateFlow()

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

    private var draggingSelection = false
    private var selectionAnchor: SelectionAnchor? = null

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

    private data class SelectionAnchor(
        val page: Int,
        val wordStartX: Float,
        val wordStartY: Float,
        val wordEndX: Float,
        val wordEndY: Float,
    )

    private data class SelectionStart(
        val snapped: com.karako.pdfr.data.SnappedSelection,
        val selection: TextSelection,
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
        selectionAnchor = null
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
                val file = withContext(Dispatchers.IO) {
                    DocumentStore.import(app, uri, name)
                }
                load(
                    file = file,
                    displayName = name ?: file.name,
                    mimeType = mime,
                    password = null,
                    startPage = 0,
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
        selectionAnchor = null

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
            zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
        )
        val previous = viewport
        if (next == previous) return
        viewport = next

        val doc = document ?: return
        if (doc.isReflowable) {
            scheduleReflow(doc, next)
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

    fun startSelection(page: Int, x: Float, y: Float) {
        val doc = document ?: return
        selectionJob?.cancel()
        val version = ++selectionVersion

        selectionAnchor = null
        draggingSelection = true
        _selection.value = null

        selectionJob = viewModelScope.launch {
            val start = withContext(MuPdf.dispatcher) {
                val snapped = doc.snapWord(page, x, y) ?: return@withContext null
                val text = doc.select(
                    page,
                    snapped.startX,
                    snapped.startY,
                    snapped.endX,
                    snapped.endY,
                ) ?: return@withContext null
                if (text.text.isBlank() || text.quads.isEmpty()) return@withContext null
                SelectionStart(snapped, text)
            }

            if (version != selectionVersion) return@launch
            if (start == null) {
                clearSelection()
                return@launch
            }

            selectionAnchor = SelectionAnchor(
                page = page,
                wordStartX = start.snapped.startX,
                wordStartY = start.snapped.startY,
                wordEndX = start.snapped.endX,
                wordEndY = start.snapped.endY,
            )
            publish(page, start.selection)
        }
    }

    fun updateSelection(page: Int, x: Float, y: Float) {
        val doc = document ?: return
        val anchor = selectionAnchor ?: return
        if (page != anchor.page || !draggingSelection) return

        selectionJob?.cancel()
        val version = ++selectionVersion

        selectionJob = viewModelScope.launch {
            // Coalesce a burst of touch moves into one native selection.
            delay(SELECTION_UPDATE_DELAY_MS)

            val result = withContext(MuPdf.dispatcher) {
                val bounds = doc.pageBounds(page) ?: return@withContext null
                val clampedX = x.coerceIn(bounds.x0, bounds.x1)
                val clampedY = y.coerceIn(bounds.y0, bounds.y1)

                // Extend from whichever edge of the long-pressed word is
                // farther from the finger, so dragging either way is natural.
                // Keep the points in reading order; MuPDF's highlight/copy
                // expect the start of the selection first.
                val afterWord = clampedY > anchor.wordEndY + 0.5f ||
                    (kotlin.math.abs(clampedY - anchor.wordEndY) <= 0.5f &&
                        clampedX >= anchor.wordEndX)
                val startX = if (afterWord) anchor.wordStartX else clampedX
                val startY = if (afterWord) anchor.wordStartY else clampedY
                val endX = if (afterWord) clampedX else anchor.wordEndX
                val endY = if (afterWord) clampedY else anchor.wordEndY

                doc.select(page, startX, startY, endX, endY)
            } ?: return@launch

            if (version != selectionVersion) return@launch
            if (result.text.isBlank() || result.quads.isEmpty()) return@launch
            publish(page, result)
        }
    }

    fun finishSelection() {
        draggingSelection = false
        _selection.value = _selection.value?.copy(dragging = false)
    }

    fun selectAll(page: Int) {
        val doc = document ?: return
        selectionJob?.cancel()
        val version = ++selectionVersion

        selectionAnchor = null
        draggingSelection = false

        selectionJob = viewModelScope.launch {
            val result = withContext(MuPdf.dispatcher) { doc.selectAll(page) }
            if (version != selectionVersion) return@launch
            if (result == null || result.text.isBlank() || result.quads.isEmpty()) {
                clearSelection()
            } else {
                publish(page, result)
            }
        }
    }

    fun clearSelection() {
        selectionVersion++
        selectionJob?.cancel()
        selectionJob = null
        selectionAnchor = null
        draggingSelection = false
        _selection.value = null
    }

    private fun publish(page: Int, selection: TextSelection) {
        _selection.value = SelectionState(
            page = page,
            quads = selection.quads,
            text = selection.text,
            dragging = draggingSelection,
        )
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

        _state.value = ReaderState.Loading(name)
        openJob = viewModelScope.launch {
            load(
                file = file,
                displayName = name,
                mimeType = mime,
                password = null,
                startPage = savedState.get<Int>(KEY_PAGE) ?: 0,
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
    ): Boolean {
        val version = ++generation

        layoutJob?.cancel()
        selectionJob?.cancel()
        selectionAnchor = null
        draggingSelection = false
        _selection.value = null

        try {
            val (opened, info) = withContext(MuPdf.dispatcher) {
                val doc = MuPdfDocument.open(file, displayName, password)
                try {
                    var appliedLayout: LayoutSpec? = null
                    if (doc.isReflowable) {
                        if (doc.isPlainText) doc.applyMonospace(monospace)
                        val spec = layoutSpec(viewport ?: defaultViewport())
                        if (doc.layout(spec.widthPt, spec.heightPt, spec.em)) {
                            appliedLayout = spec
                        }
                    }
                    val documentInfo = doc.info()
                    if (documentInfo.pageCount <= 0) {
                        throw IOException(
                            getApplication<Application>().getString(R.string.error_no_pages),
                        )
                    }
                    lastLayout = appliedLayout
                    doc to documentInfo
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
            savedState[KEY_NAME] = displayName
            savedState[KEY_MIME] = mimeType
            savedState[KEY_PAGE] = page
            savedState[KEY_MONO] = monospace

            _state.value = ReaderState.Ready(
                file = file,
                displayName = displayName,
                mimeType = mimeType,
                info = info,
                pageCount = pageCount,
                reflowable = opened.isReflowable,
                monospace = monospace,
                monospaceAvailable = opened.isPlainText,
                anchorPage = page,
            )

            if (previous != null && previous !== opened) {
                MuPdf.execute { previous.close() }
            }
            return true
        } catch (e: DocumentPasswordRequiredException) {
            if (!preservePrevious) {
                discardPreviousDocument()
                lock(file, displayName, mimeType, wrongPassword = false)
            }
            return false
        } catch (e: DocumentWrongPasswordException) {
            if (!preservePrevious) {
                discardPreviousDocument()
                lock(file, displayName, mimeType, wrongPassword = true)
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

        layoutJob?.cancel()
        val version = generation

        layoutJob = viewModelScope.launch {
            delay(REFLOW_DEBOUNCE_MS)

            val result = withContext(MuPdf.dispatcher) {
                val mark = doc.makeBookmark(currentPage)
                doc.applyMonospace(monospace)
                if (!doc.layout(spec.widthPt, spec.heightPt, spec.em)) {
                    return@withContext null
                }
                lastLayout = spec
                val page = doc.pageForBookmark(mark)
                    ?: currentPage
                LayoutResult(
                    page = page,
                    pageCount = doc.countPages(),
                    info = doc.info(),
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

        val doc = document ?: return
        scheduleReflow(doc, viewport ?: defaultViewport(), force = true)
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

    /** Uses qpdf to write a genuinely linearized replacement PDF. */
    fun linearizePdf() {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (!ready.info.isPdf) return

        rewrite(ready, pages = null, rotate = null, kind = RewriteKind.LINEARIZE)
    }

    /**
     * Rewrites the current PDF into a compact, fully compressed copy. This is
     * MuPDF's closest replacement for linearization and stays available next
     * to the qpdf-powered Linearize action.
     */
    fun compactPdf() {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (!ready.info.isPdf) return

        rewrite(ready, pages = null, rotate = null, kind = RewriteKind.COMPACT)
    }

    private fun rewrite(
        ready: ReaderState.Ready,
        pages: IntArray?,
        rotate: IntArray?,
        kind: RewriteKind,
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
                    if (kind == RewriteKind.LINEARIZE) Qpdf.dispatcher else MuPdf.dispatcher,
                ) {
                    val target = DocumentStore.derivedFile(app, derivedDisplayName(ready.displayName, kind))
                    val saved = if (kind == RewriteKind.LINEARIZE) {
                        val error = Qpdf.linearize(
                            source = ready.file,
                            destination = target,
                            password = password,
                        )
                        failure = error
                        error == null
                    } else {
                        MuPdfDocument.savePdfCopy(
                            source = ready.file,
                            password = password,
                            destination = target,
                            pageIndices = pages,
                            rotatePages = rotate,
                            options = MuPdfDocument.PDF_COMPACT_OPTIONS,
                        )
                    }
                    if (saved) target else {
                        target.delete()
                        null
                    }
                }

                val created = output
                if (created == null) {
                    val message = when {
                        kind == RewriteKind.LINEARIZE &&
                            failure?.contains("not available") == true ->
                            R.string.linearize_unavailable
                        else -> R.string.rewrite_failed
                    }
                    _messages.tryEmit(app.getString(message))
                    return@launch
                }

                val replaced = load(
                    file = created,
                    displayName = derivedDisplayName(ready.displayName, kind),
                    mimeType = DocumentFormats.PDF,
                    password = password,
                    startPage = 0,
                    preservePrevious = true,
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
        LINEARIZE("linearized", R.string.linearize_done),
        COMPACT("compact", R.string.compact_done),
    }

    private fun layoutSpec(viewport: Viewport): LayoutSpec {
        val pointsPerPixel = 72f / viewport.densityDpi
        val zoom = viewport.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return LayoutSpec(
            widthPt = (viewport.widthPx * pointsPerPixel / zoom).coerceAtLeast(32f),
            heightPt = (viewport.heightPx * pointsPerPixel / zoom).coerceAtLeast(32f),
            em = TEXT_EM,
        )
    }

    private fun defaultViewport(): Viewport {
        val metrics = getApplication<Application>().resources.displayMetrics
        val margin = (16 * metrics.density).toInt()
        return Viewport(
            widthPx = (metrics.widthPixels - 2 * margin).coerceAtLeast(200),
            heightPx = (metrics.heightPixels - 2 * margin).coerceAtLeast(300),
            densityDpi = metrics.densityDpi,
            zoom = 1f,
        )
    }

    private fun lock(file: File, displayName: String, mimeType: String, wrongPassword: Boolean) {
        savedState[KEY_PATH] = file.absolutePath
        savedState[KEY_NAME] = displayName
        savedState[KEY_MIME] = mimeType

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

    private companion object {
        const val KEY_PATH = "document_path"
        const val KEY_NAME = "document_name"
        const val KEY_MIME = "document_mime"
        const val KEY_PAGE = "document_page"
        const val KEY_MONO = "document_monospace"

        const val TEXT_EM = 11f
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
        const val SELECTION_UPDATE_DELAY_MS = 16L
        const val REFLOW_DEBOUNCE_MS = 140L
        const val BITMAP_EVICT_DEBOUNCE_MS = 240L
    }
}
