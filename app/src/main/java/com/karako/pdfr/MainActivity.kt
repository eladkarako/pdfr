package com.karako.pdfr

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karako.pdfr.data.AppSettings
import com.karako.pdfr.data.ThemeMode
import com.karako.pdfr.ui.ReaderApp
import com.karako.pdfr.ui.ReaderViewModel
import com.karako.pdfr.ui.theme.PDFrTheme
import com.karako.pdfr.ui.theme.isDark

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    // Read once before the first frame so the stored language is already active
    // when the UI is composed. The theme only needs a recomposition, while a
    // language change recreates the activity.
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var languageTag by mutableStateOf(AppSettings.SYSTEM_LANGUAGE)

    override fun attachBaseContext(newBase: Context) {
        // The language override has to be applied before the activity resolves
        // any resources, otherwise the first frame would use the system locale.
        super.attachBaseContext(AppSettings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themeMode = AppSettings.themeMode(this)
        languageTag = AppSettings.languageTag(this)
        applySystemBars()

        // On rotation the ViewModel already holds the document; only act on the
        // launch intent when this is genuinely a fresh start.
        if (savedInstanceState == null) {
            openFrom(intent)
        }

        setContent {
            // Keep the system bars readable when a forced theme differs from
            // the device setting. A language change recreates the activity, so
            // this only follows theme changes.
            LaunchedEffect(themeMode) { applySystemBars() }

            PDFrTheme(themeMode = themeMode) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val selection by viewModel.selection.collectAsStateWithLifecycle()
                ReaderApp(
                    state = state,
                    selection = selection,
                    viewModel = viewModel,
                    themeMode = themeMode,
                    languageTag = languageTag,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        AppSettings.setThemeMode(this@MainActivity, mode)
                    },
                    onLanguageChange = { tag ->
                        if (tag != languageTag) {
                            AppSettings.setLanguageTag(this@MainActivity, tag)
                            languageTag = tag
                            recreate()
                        }
                    },
                )
            }
        }
    }

    /** Applies the icon colours that match the currently forced theme. */
    private fun applySystemBars() {
        val systemDark = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = themeMode.isDark(systemDark)
        // Transparent scrims keep the edge-to-edge layout; Android enforces a
        // contrast scrim for three-button navigation on its own.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent?) {
        val uri = intent?.documentUri() ?: return
        // The file is copied immediately while the grant is live, so there is no
        // need to hold a persistable permission.
        viewModel.open(uri)
    }

    private fun Intent.documentUri(): Uri? = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
            this,
            Intent.EXTRA_STREAM,
            Uri::class.java
        )

        else -> null
    }
}
