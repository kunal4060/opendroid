package com.opendroid.ai.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * OpenDroid color palette — resolved via CompositionLocal so all screens
 * automatically adapt to the active theme (light / dark).
 */
data class OpenDroidColors(
    val background: Color,
    val surface: Color,
    val cardBackground: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /**
     * The primary accent (thin marks: icons, links, selected states), driven by
     * the user's [AccentTheme] choice via [resolveOpenDroidColors]. The field
     * keeps its historical name; with the default Signal Green preset it holds
     * an actual green.
     */
    val accentNeonGreen: Color,
    /** Calmer step of the accent for large filled button surfaces. */
    val accentGreenButton: Color,
    /** Content colour drawn on top of [accentGreenButton] fills. */
    val onAccent: Color,
    /**
     * Legacy secondary-accent tokens. Screens read these as "the accent"
     * ([accentCyan] is the most-read colour token in the app), so the resolver
     * aliases both to the chosen accent — one accent everywhere, and no screen
     * keeps producing a competing hue the user did not pick.
     */
    val accentPurple: Color,
    val accentCyan: Color,
    val accentRed: Color,
    val accentOrange: Color = Color(0xFFFF9500),
    val isDark: Boolean
)

// ── Base palettes ────────────────────────────────────────────
// Neutral surfaces + text + semantic colours. The accent fields below carry
// the DEFAULT preset (Signal Green) so these palettes are valid on their own
// (CompositionLocal default, edge-to-edge scrims); OpenDroidTheme always goes
// through resolveOpenDroidColors() to apply the user's actual choice.
// Semantic colours (accentRed = error, accentOrange = warning) are part of
// the base on purpose: the accent picker never moves them, so they remain
// distinguishable under every accent.

// ── Dark palette: Classic Pure Black (#000000) ───────────────
val DarkPalette = OpenDroidColors(
    background = Color(0xFF000000),      // Classic pure OLED black
    surface = Color(0xFF0A0A0A),         // Deep charcoal surface
    cardBackground = Color(0xFF141416),  // Elevated card surface
    borderColor = Color(0xFF27272A),     // Zinc 800 hairline border
    textPrimary = Color(0xFFFFFFFF),     // Pure white
    textSecondary = Color(0xFFA1A1AA),   // Zinc 400 silver
    accentNeonGreen = Color(0xFF3ECF8E), // Signal Green marks (default preset)
    accentGreenButton = Color(0xFF2FBF7F), // Signal Green fill
    onAccent = Color(0xFF052418),
    accentPurple = Color(0xFF3ECF8E),    // Legacy alias of the accent
    accentCyan = Color(0xFF3ECF8E),      // Legacy alias of the accent
    accentRed = Color(0xFFEF4444),       // Clean alert red
    accentOrange = Color(0xFFFF9500),
    isDark = true
)

// ── Light palette: Classic Pure White (#FFFFFF) ─────────────
val LightPalette = OpenDroidColors(
    background = Color(0xFFFFFFFF),      // Classic pure white
    surface = Color(0xFFF8F9FA),         // Crisp light surface
    cardBackground = Color(0xFFFFFFFF),  // Pure white card
    borderColor = Color(0xFFE4E4E7),     // Zinc 200 hairline border
    textPrimary = Color(0xFF09090B),     // Deep obsidian black
    textSecondary = Color(0xFF71717A),   // Zinc 500 slate
    accentNeonGreen = Color(0xFF0B7A44), // Signal Green marks (default preset)
    accentGreenButton = Color(0xFF0E8A4C), // Signal Green fill
    onAccent = Color(0xFFFFFFFF),
    accentPurple = Color(0xFF0B7A44),    // Legacy alias of the accent
    accentCyan = Color(0xFF0B7A44),      // Legacy alias of the accent
    accentRed = Color(0xFFDC2626),       // Clean alert red
    accentOrange = Color(0xFFD97706),
    isDark = false
)

/**
 * Resolves the palette for a brightness mode + the user's accent choice.
 *
 * The chosen accent is written into every accent token screens genuinely
 * read — [OpenDroidColors.accentNeonGreen], [OpenDroidColors.accentGreenButton]
 * and the legacy [OpenDroidColors.accentCyan] / [OpenDroidColors.accentPurple]
 * aliases — so a picker change re-skins the whole app on the next
 * recomposition, with no restart and no dead setting.
 *
 * When Material You dynamic colour is active, the caller passes the dynamic
 * scheme's `primary` / `onPrimary` and they override the preset.
 * Semantic colours always come from the base palette, untouched.
 */
fun resolveOpenDroidColors(
    dark: Boolean,
    accentTheme: AccentTheme,
    dynamicPrimary: Color? = null,
    dynamicOnPrimary: Color? = null
): OpenDroidColors {
    val base = if (dark) DarkPalette else LightPalette
    val values = accentTheme.values(dark)
    val marks = dynamicPrimary ?: values.marks
    val fill = dynamicPrimary ?: values.fill
    return base.copy(
        accentNeonGreen = marks,
        accentGreenButton = fill,
        onAccent = dynamicOnPrimary ?: values.onFill,
        accentPurple = marks,
        accentCyan = marks
    )
}

val LocalOpenDroidColors = compositionLocalOf { DarkPalette }

/** Access the active palette from any @Composable */
object AppTheme {
    val colors: OpenDroidColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenDroidColors.current
}

// ── Dynamic Composable top-level aliases ────────────────────
// These allow all existing screens and composables to automatically
// adapt to Light / Dark theme dynamically without hardcoding DarkPalette!

val DarkBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.background

val DarkSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surface

val CardBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.cardBackground

val BorderColor: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.borderColor

val TextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.textPrimary

val TextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.textSecondary

val AccentNeonGreen: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentNeonGreen

val AccentGreenButton: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentGreenButton

val OnAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.onAccent

val AccentPurple: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentPurple

val AccentCyan: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentCyan

val AccentRed: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentRed

val AccentOrange: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentOrange

