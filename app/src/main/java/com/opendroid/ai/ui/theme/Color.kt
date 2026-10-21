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
 *
 * Values are the "Obsidian & Signal" tokens from
 * research-3-design-system.md §3.1 (dark) / §3.2 (light): warm-charcoal
 * neutral tiers, hairline borders as alpha ARGB, one brand accent
 * (Signal Green), and muted semantics earned only by meaning
 * (info steel blue, permission amber, error red, success = green family).
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
     * Legacy secondary-accent tokens, re-mapped per research-3 §2.2 rule 5
     * and §3: [accentPurple] is a deprecated alias of the info colour and
     * [accentCyan] is a deprecated alias of Signal Green in the static
     * palettes — no screen keeps producing purple or cyan. See
     * [resolveOpenDroidColors] for how the picker interacts with them.
     */
    val accentPurple: Color,
    val accentCyan: Color,
    val accentRed: Color,
    val accentOrange: Color = Color(0xFFE5B45B),
    // ── Additive Obsidian & Signal fields (research-3 §3) ─────────────
    /** Tier 3 surface: menus, plan cards, dialogs (§3.1/§3.2). */
    val surfaceRaised: Color,
    /** Tier 4 surface: tooltips, popovers, highest containers. */
    val surfaceOverlay: Color,
    /** Tier −1 surface: code blocks, log wells, inset input wells. */
    val surfaceSunken: Color,
    /** Divider hairline (white 6% dark / black 8% light). */
    val borderSubtle: Color,
    /** Interactive-edge hairline (white 16% dark / black 18% light). */
    val borderStrong: Color,
    /** Third text tier: timestamps, hints, disabled (use at ≥12sp only). */
    val textTertiary: Color,
    /** Selected chip / running-step background (green container). */
    val accentContainer: Color,
    /** Content colour drawn on top of [accentContainer]. */
    val onAccentContainer: Color,
    /** Muted steel blue: external links and info banners only. */
    val accentInfo: Color,
    /** Success/completion colour — shares the Signal Green family. */
    val success: Color,
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

// ── Dark palette: Obsidian (research-3 §3.1) ────────────────
val DarkPalette = OpenDroidColors(
    background = Color(0xFF0A0A0B),      // Warm near-black
    surface = Color(0xFF101012),         // Tier 1
    cardBackground = Color(0xFF161618),  // Tier 2
    borderColor = Color(0x1AFFFFFF),     // Hairline: white @ 10%
    textPrimary = Color(0xFFF4F4F2),     // Warm off-white
    textSecondary = Color(0xFFA8A8A3),   // Warm grey
    accentNeonGreen = Color(0xFF3ECF8E), // Signal Green marks (default preset)
    accentGreenButton = Color(0xFF2FBF7F), // Signal Green fill
    onAccent = Color(0xFF052418),
    accentPurple = Color(0xFF7C9CC4),    // Deprecated alias of accentInfo
    accentCyan = Color(0xFF3ECF8E),      // Deprecated alias of Signal Green
    accentRed = Color(0xFFF07575),       // Soft coral-red error/destructive
    accentOrange = Color(0xFFE5B45B),    // Warning / permission amber
    surfaceRaised = Color(0xFF1D1D20),   // Tier 3
    surfaceOverlay = Color(0xFF26262B),  // Tier 4
    surfaceSunken = Color(0xFF060607),   // Tier −1
    borderSubtle = Color(0x0FFFFFFF),    // White @ 6%
    borderStrong = Color(0x29FFFFFF),    // White @ 16%
    textTertiary = Color(0xFF6F6F6A),
    accentContainer = Color(0xFF143327),
    onAccentContainer = Color(0xFFA9EBCB),
    accentInfo = Color(0xFF7C9CC4),      // Muted steel blue
    success = Color(0xFF3ECF8E),         // Green family
    isDark = true
)

// ── Light palette: Warm paper (research-3 §3.2) ────────────
val LightPalette = OpenDroidColors(
    background = Color(0xFFFAFAF8),      // Warm paper white
    surface = Color(0xFFF3F3F0),         // Tier 1
    cardBackground = Color(0xFFFFFFFF),  // Tier 2: white card
    borderColor = Color(0x1F000000),     // Hairline: black @ 12%
    textPrimary = Color(0xFF141416),
    textSecondary = Color(0xFF5A5A56),
    accentNeonGreen = Color(0xFF0B7A44), // Signal Green marks, deepened for light
    accentGreenButton = Color(0xFF0E8A4C), // Signal Green fill (§3.2)
    onAccent = Color(0xFFFFFFFF),
    accentPurple = Color(0xFF33618F),    // Deprecated alias of accentInfo
    accentCyan = Color(0xFF0B7A44),      // Deprecated alias of Signal Green
    accentRed = Color(0xFFB3382E),
    accentOrange = Color(0xFF96690F),    // Warning / permission amber-brown
    surfaceRaised = Color(0xFFFFFFFF),   // Tier 3: white + borderStrong + shadow
    surfaceOverlay = Color(0xFFFFFFFF),  // Tier 4
    surfaceSunken = Color(0xFFECECE8),   // Code/log wells
    borderSubtle = Color(0x14000000),    // Black @ 8%
    borderStrong = Color(0x2E000000),    // Black @ 18%
    textTertiary = Color(0xFF8A8A84),
    accentContainer = Color(0xFFDCF2E5),
    onAccentContainer = Color(0xFF0A3D24),
    accentInfo = Color(0xFF33618F),
    success = Color(0xFF0B7A44),         // Green family, deepened for light
    isDark = false
)

/**
 * Resolves the palette for a brightness mode + the user's accent choice.
 *
 * The chosen accent is written into the accent tokens screens genuinely
 * read as "the accent" — [OpenDroidColors.accentNeonGreen],
 * [OpenDroidColors.accentGreenButton] and the legacy
 * [OpenDroidColors.accentCyan] alias (the most-read colour token in the
 * app) — so a picker change re-skins the whole app on the next
 * recomposition, with no restart and no dead setting. Under the default
 * Signal Green preset, `accentCyan` therefore resolves to exactly the
 * §3 Signal Green value (#3ECF8E dark / #0B7A44 light).
 *
 * [OpenDroidColors.accentPurple] is NOT driven by the picker: per
 * research-3 §3 it is a deprecated alias of the static info colour
 * ([OpenDroidColors.accentInfo]) in both palettes, so it stays steel
 * blue under every accent. Likewise `accentContainer`,
 * `onAccentContainer` and `success` stay in the Signal Green family
 * from the base palette (success is the brand moment, §2.2 rule 4).
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
        accentCyan = marks
        // accentPurple intentionally left at the base value: the §3
        // deprecated alias of accentInfo, not a second picker-driven hue.
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

// ── Additive aliases for the Obsidian & Signal fields ───────

val SurfaceRaised: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surfaceRaised

val SurfaceOverlay: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surfaceOverlay

val SurfaceSunken: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surfaceSunken

val BorderSubtle: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.borderSubtle

val BorderStrong: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.borderStrong

val TextTertiary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.textTertiary

val AccentContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentContainer

val OnAccentContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.onAccentContainer

val AccentInfo: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentInfo

val Success: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.success
