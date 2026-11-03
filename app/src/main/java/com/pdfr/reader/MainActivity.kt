package com.karako.pdfr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karako.pdfr.ui.ReaderApp
import com.karako.pdfr.ui.ReaderViewModel
import com.karako.pdfr.ui.theme.PDFrTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // On rotation the ViewModel already holds the document; only act on the
        // launch intent when this is genuinely a fresh start.
        if (savedInstanceState == null) {
            openFrom(intent)
        }

        setContent {
            PDFrTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val documentInfo by viewModel.documentInfo.collectAsStateWithLifecycle()
                ReaderApp(
                    state = state,
                    documentInfo = documentInfo,
                    viewModel = viewModel,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent?) {
        val uri = intent?.pdfUri() ?: return
        // The file is copied immediately while the grant is live, so there is no
        // need to hold a persistable permission.
        viewModel.open(uri)
    }

    private fun Intent.pdfUri(): Uri? = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
