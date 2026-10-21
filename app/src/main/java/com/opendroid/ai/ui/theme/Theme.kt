package com.opendroid.ai.ui.theme

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Applies edge-to-edge using the app's selected theme rather than the device's system theme.
 *
 * AndroidX uses the system UI mode to select the API 26-28 navigation-bar scrim by default.
 * OpenDroid supports an in-app theme toggle, so use the selected palette instead. On API 29+
 * [SystemBarStyle.auto] keeps both bars transparent, preserving edge-to-edge behavior.
 */
internal fun ComponentActivity.enableOpenDroidEdgeToEdge(isDarkTheme: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            lightScrim = Color.TRANSPARENT,
            darkScrim = Color.TRANSPARENT,
            detectDarkMode = { isDarkTheme }
        ),
        navigationBarStyle = SystemBarStyle.auto(
            lightScrim = LightPalette.background.toArgb(),
            darkScrim = DarkPalette.background.toArgb(),
            detectDarkMode = { isDarkTheme }
        )
    )
}

/**
 * Builds the M3 scheme from the RESOLVED palette, so stock Material
 * components (dialogs, menus, switches) follow the same accent the screens
 * read through [AppTheme.colors] instead of a fixed palette.
 */
private fun colorSchemeFor(palette: OpenDroidColors): ColorScheme =
    if (palette.isDark) {
        darkColorScheme(
            primary = palette.accentGreenButton,
            secondary = palette.accentPurple,
            tertiary = palette.accentCyan,
            background = palette.background,
            surface = palette.surface,
            onPrimary = palette.onAccent,
            onSecondary = palette.textPrimary,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            error = palette.accentRed
        )
    } else {
        lightColorScheme(
            primary = palette.accentGreenButton,
            secondary = palette.accentPurple,
            tertiary = palette.accentCyan,
            background = palette.background,
            surface = palette.surface,
            onPrimary = palette.onAccent,
            onSecondary = palette.textPrimary,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            error = palette.accentRed
        )
    }

@Composable
fun OpenDroidTheme(
    isDarkTheme: Boolean = true,
    accentTheme: AccentTheme = AccentTheme.DEFAULT,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Material You dynamic colour exists only on Android 12 (API 31)+; the
    // Settings toggle is offered on those devices only. When active, the
    // dynamic scheme drives Material components AND the accent tokens screens
    // read (via its primary), overriding the preset.
    val context = LocalContext.current
    val dynamicColorScheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }

    val palette = resolveOpenDroidColors(
        dark = isDarkTheme,
        accentTheme = accentTheme,
        dynamicPrimary = dynamicColorScheme?.primary,
        dynamicOnPrimary = dynamicColorScheme?.onPrimary
    )
    val colorScheme = dynamicColorScheme ?: colorSchemeFor(palette)

    val view = LocalView.current
    val activity = view.context as? ComponentActivity
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            activity.enableOpenDroidEdgeToEdge(isDarkTheme)
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Light status bar icons for dark theme, dark icons for light theme
            insetsController.isAppearanceLightStatusBars = !isDarkTheme
            insetsController.isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    CompositionLocalProvider(LocalOpenDroidColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
