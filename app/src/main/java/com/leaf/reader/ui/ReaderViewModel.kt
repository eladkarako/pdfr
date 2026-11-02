package com.leaf.reader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.leaf.reader.R
import com.leaf.reader.data.DocumentStore
import com.leaf.reader.data.PdfDocumentInfo
import com.leaf.reader.data.PdfDocuments
import com.leaf.reader.data.PdfPasswordRequiredException
import com.leaf.reader.data.PdfWrongPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ReaderState {
    data object Empty : ReaderState
    data class Loading(val name: String?) : ReaderState
    data class Ready(val file: File, val displayName: String) : ReaderState
    data class Locked(
        val file: File,
        val displayName: String,
        val wrongPassword: Boolean = false,
    ) : ReaderState

    data class Error(val message: String) : ReaderState
}

class ReaderViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(restoredState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _documentInfo = MutableStateFlow<PdfDocumentInfo?>(null)
    val documentInfo: StateFlow<PdfDocumentInfo?> = _documentInfo.asStateFlow()

    private var importJob: Job? = null

    init {
        val keep = when (val current = _state.value) {
            is ReaderState.Ready -> current.file
            is ReaderState.Locked -> current.file
            else -> null
        }
        viewModelScope.launch { DocumentStore.cleanup(getApplication(), keep) }

        // The file survives process death, but the metadata is cheap to read
        // again and not worth putting in the saved-state bundle.
        if (_state.value is ReaderState.Ready) refreshInfo()
    }

    fun open(uri: Uri) {
        val app = getApplication<Application>()

        // Sharing Leaf's own PDF back to Leaf would only re-import the file that
        // is already open. Leave the viewer (and its page position) untouched.
        val current = (_state.value as? ReaderState.Ready)?.file
        if (current != null && uri.authority == "${app.packageName}.fileprovider") {
            return
        }

        importJob?.cancel()
        _documentInfo.value = null

        val name = DocumentStore.displayName(app, uri)
        _state.value = ReaderState.Loading(name)

        importJob = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    DocumentStore.import(app, uri, name)
                }
                load(file = file, displayName = name ?: file.name, password = null)
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

        importJob?.cancel()
        _documentInfo.value = null
        _state.value = ReaderState.Loading(locked.displayName)

        importJob = viewModelScope.launch {
            load(locked.file, locked.displayName, password)
        }
    }

    fun onRenderError(error: Throwable) {
        _state.value = ReaderState.Error(
            error.message?.takeIf { it.isNotBlank() }
                ?: getApplication<Application>().getString(R.string.error_generic),
        )
    }

    private suspend fun load(file: File, displayName: String, password: String?) {
        try {
            val prepared = withContext(Dispatchers.IO) {
                PdfDocuments.prepare(file, displayName, password)
            }

            savedState[KEY_PATH] = prepared.file.absolutePath
            savedState[KEY_NAME] = displayName
            savedState[KEY_LOCKED] = false
            savedState[KEY_ENCRYPTED] = prepared.info.encrypted
            savedState[KEY_SIZE] = prepared.info.sizeBytes

            _documentInfo.value = prepared.info
            _state.value = ReaderState.Ready(prepared.file, displayName)
        } catch (e: PdfPasswordRequiredException) {
            lock(file, displayName, wrongPassword = false)
        } catch (e: PdfWrongPasswordException) {
            lock(file, displayName, wrongPassword = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun lock(file: File, displayName: String, wrongPassword: Boolean) {
        savedState[KEY_PATH] = file.absolutePath
        savedState[KEY_NAME] = displayName
        savedState[KEY_LOCKED] = true
        savedState[KEY_ENCRYPTED] = true
        savedState[KEY_SIZE] = file.length()

        _documentInfo.value = null
        _state.value = ReaderState.Locked(file, displayName, wrongPassword)
    }

    private fun fail(error: Exception) {
        _state.value = ReaderState.Error(
            error.message?.takeIf { it.isNotBlank() }
                ?: getApplication<Application>().getString(R.string.error_generic),
        )
    }

    private fun refreshInfo() {
        val ready = _state.value as? ReaderState.Ready ?: return

        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { PdfDocuments.inspect(ready.file, ready.displayName) }.getOrNull()
            } ?: return@launch

            if ((_state.value as? ReaderState.Ready)?.file != ready.file) return@launch

            // The decrypted copy is no longer encrypted, but the document the
            // user opened still was. Its size also belongs to the source file,
            // not to the rewritten cache copy.
            val originallyEncrypted = savedState.get<Boolean>(KEY_ENCRYPTED) == true
            val originalSize = savedState.get<Long>(KEY_SIZE)
            _documentInfo.value = info.copy(
                sizeBytes = originalSize ?: info.sizeBytes,
                encrypted = originallyEncrypted || info.encrypted,
            )
        }
    }

    private fun restoredState(): ReaderState {
        val path = savedState.get<String>(KEY_PATH) ?: return ReaderState.Empty
        val file = File(path)
        if (!file.isFile) return ReaderState.Empty

        val displayName = savedState.get<String>(KEY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: file.name

        return if (savedState.get<Boolean>(KEY_LOCKED) == true) {
            ReaderState.Locked(file, displayName)
        } else {
            ReaderState.Ready(file, displayName)
        }
    }

    private companion object {
        const val KEY_PATH = "document_path"
        const val KEY_NAME = "document_name"
        const val KEY_LOCKED = "document_locked"
        const val KEY_ENCRYPTED = "document_encrypted"
        const val KEY_SIZE = "document_size"
    }
}
