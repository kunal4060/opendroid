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
import androidx.compose.ui.graphics.Color as ComposeColor
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
 *
 * EVERY M3 role is filled per research-3-design-system.md §3.3, so no
 * violet M3 baseline value can leak into an unset role:
 * primary = accent fill · containers = accentContainer pair · secondary =
 * textSecondary-toned neutral with surfaceRaised containers (never a
 * second hue) · tertiary = accentInfo · surface containers = the six
 * neutral tiers · outline = borderStrong · outlineVariant = borderSubtle.
 */
private fun colorSchemeFor(palette: OpenDroidColors): ColorScheme =
    if (palette.isDark) {
        darkColorScheme(
            primary = palette.accentGreenButton,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentContainer,
            onPrimaryContainer = palette.onAccentContainer,
            secondary = palette.textSecondary,
            onSecondary = palette.background,
            secondaryContainer = palette.surfaceRaised,
            onSecondaryContainer = palette.textPrimary,
            tertiary = palette.accentInfo,
            onTertiary = ComposeColor(0xFF0D1B2A),
            tertiaryContainer = ComposeColor(0xFF25374D),
            onTertiaryContainer = ComposeColor(0xFFC7D8EC),
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.textSecondary,
            surfaceTint = palette.accentGreenButton,
            inverseSurface = ComposeColor(0xFFE8E8E4),
            inverseOnSurface = ComposeColor(0xFF1B1B1D),
            inversePrimary = palette.accentNeonGreen,
            error = palette.accentRed,
            onError = ComposeColor(0xFF3A0A0A),
            errorContainer = ComposeColor(0xFF3A1D1D),
            onErrorContainer = ComposeColor(0xFFFFDAD6),
            outline = palette.borderStrong,
            outlineVariant = palette.borderSubtle,
            scrim = ComposeColor(0x99000000),
            surfaceBright = palette.surfaceOverlay,
            surfaceDim = palette.surfaceSunken,
            surfaceContainerLowest = palette.surfaceSunken,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.cardBackground,
            surfaceContainerHigh = palette.surfaceRaised,
            surfaceContainerHighest = palette.surfaceOverlay
        )
    } else {
        lightColorScheme(
            primary = palette.accentGreenButton,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentContainer,
            onPrimaryContainer = palette.onAccentContainer,
            secondary = palette.textSecondary,
            onSecondary = ComposeColor.White,
            secondaryContainer = palette.surfaceRaised,
            onSecondaryContainer = palette.textPrimary,
            tertiary = palette.accentInfo,
            onTertiary = ComposeColor.White,
            tertiaryContainer = ComposeColor(0xFFD6E4F0),
            onTertiaryContainer = ComposeColor(0xFF0F2F4D),
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.textSecondary,
            surfaceTint = palette.accentGreenButton,
            inverseSurface = ComposeColor(0xFF1B1B1D),
            inverseOnSurface = ComposeColor(0xFFF4F4F2),
            inversePrimary = ComposeColor(0xFF3ECF8E),
            error = palette.accentRed,
            onError = ComposeColor.White,
            errorContainer = ComposeColor(0xFFF9DEDC),
            onErrorContainer = ComposeColor(0xFF410E0B),
            outline = palette.borderStrong,
            outlineVariant = palette.borderSubtle,
            scrim = ComposeColor(0x66000000),
            surfaceBright = palette.cardBackground,
            surfaceDim = palette.surfaceSunken,
            surfaceContainerLowest = palette.surfaceSunken,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.cardBackground,
            surfaceContainerHigh = palette.surfaceRaised,
            surfaceContainerHighest = palette.surfaceOverlay
        )
    }

@Composable
fun OpenDroidTheme(
    isDarkTheme: Boolean = true,
    accentTheme: AccentTheme = AccentTheme.DEFAULT,
    useDynamicColor: Boolean = false,
    /** User's Reduce motion toggle (D8); the system animator scale is OR-ed in below. */
    reduceMotion: Boolean = false,
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

    // D8: effective reduce-motion = the user's toggle OR the system
    // animator scale being 0. Resolved here so every consumer of
    // LocalReduceMotion (chat orb/dots/waveform, splash) honours both,
    // including surfaces that never see the user's config (assistant
    // session) for the system half.
    val effectiveReduceMotion = reduceMotion || systemReduceMotionEnabled(context)

    CompositionLocalProvider(
        LocalOpenDroidColors provides palette,
        LocalReduceMotion provides effectiveReduceMotion
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = OpenDroidShapes,
            content = content
        )
    }
}
