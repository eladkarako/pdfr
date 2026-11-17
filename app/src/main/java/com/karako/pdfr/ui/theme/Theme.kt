package com.karako.pdfr.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.karako.pdfr.data.ThemeMode

/**
 * Dark palette with grey surfaces, so it is easier on the eyes than pure black
 * while still being clearly darker than the light theme.
 */
private val GrayishDarkColors = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF2B2B31),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF45464F),
    // The default dark containers are tinted purple; keep the elevated
    // surfaces (menus, dialogs) in the same neutral grey as the rest.
    surfaceContainerLowest = Color(0xFF0D0D10),
    surfaceContainerLow = Color(0xFF17171B),
    surfaceContainer = Color(0xFF1B1B1F),
    surfaceContainerHigh = Color(0xFF252529),
    surfaceContainerHighest = Color(0xFF303035),
)

/**
 * Pure-black palette for OLED panels. Surfaces stay black so unlit pixels use
 * no power, while outlines and secondary text keep enough contrast to read.
 */
private val AmoledBlackColors = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF202A36),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF2E2236),
    onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color.Black,
    onBackground = Color(0xFFECECEC),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFFC2C2C2),
    outline = Color(0xFF7C7C7C),
    outlineVariant = Color(0xFF2A2A2A),
    // Menus and dialogs stay near-black so the whole theme reads as AMOLED
    // black; the slight lift keeps their edges visible against the black
    // background.
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF0F0F0F),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1A1A1A),
)

/** True when [this] mode should use a dark status bar and dark palette. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFrTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = themeMode.isDark(systemDark)

    val colorScheme = when (themeMode) {
        ThemeMode.SYSTEM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (systemDark) {
                GrayishDarkColors
            } else {
                lightColorScheme()
            }

        ThemeMode.LIGHT -> lightColorScheme()
        ThemeMode.DARK -> GrayishDarkColors
        ThemeMode.AMOLED -> AmoledBlackColors
    }

    MaterialTheme(colorScheme = colorScheme) {
        // Material3 components pick up the primary-coloured ripple, while the
        // foundation local covers selectable rows and other non-Material
        // clickables. Both make a press easy to see on a dim TV screen.
        CompositionLocalProvider(
            LocalRippleConfiguration provides RippleConfiguration(color = colorScheme.primary),
            LocalIndication provides ripple(color = colorScheme.primary),
        ) {
            content()
        }
    }
}
