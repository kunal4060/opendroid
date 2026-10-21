package com.opendroid.ai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * User-selectable accent themes (Settings → Appearance → Accent Colour).
 *
 * Each preset contributes three values per brightness mode:
 *  - [Values.marks] — thin accent marks: icons, links, selected states, and the
 *    legacy `accentCyan` / `accentPurple` tokens that screens actually read as
 *    "the accent" (see [resolveOpenDroidColors]).
 *  - [Values.fill] — calmer step of the same hue for large filled surfaces
 *    (primary buttons); written to `accentGreenButton` and the M3 `primary`.
 *  - [Values.onFill] — content colour drawn on top of [Values.fill].
 *
 * Semantic colours are deliberately NOT part of a preset: error red and
 * warning orange live on the base palettes and never move with the accent, so
 * they stay distinguishable under every choice here (none of these hues is a
 * red or an orange).
 *
 * Values follow research-3-design-system.md §3: accents are desaturated for
 * dark mode and deepened (not reused) for light mode.
 */
enum class AccentTheme(val displayName: String) {
    SIGNAL_GREEN("Signal Green"),
    CYAN("Cyan"),
    VIOLET("Violet"),
    STEEL_BLUE("Steel Blue"),
    MONOCHROME("Monochrome");

    data class Values(
        val marks: Color,
        val fill: Color,
        val onFill: Color
    )

    private val darkValues: Values
        get() = when (this) {
            // Brand accent: README heritage green #00FF88, desaturated/deepened.
            SIGNAL_GREEN -> Values(
                marks = Color(0xFF3ECF8E),
                fill = Color(0xFF2FBF7F),
                onFill = Color(0xFF052418)
            )
            // The de-facto accent the UI shipped with (marks = old accentCyan).
            // Fill deepened #0284C7 → #0369A1: white onFill on the old fill
            // was 4.10:1 (below WCAG AA for button text); now 5.93:1 (D8).
            CYAN -> Values(
                marks = Color(0xFF38BDF8),
                fill = Color(0xFF0369A1),
                onFill = Color(0xFFFFFFFF)
            )
            // Marks = the old accentPurple.
            VIOLET -> Values(
                marks = Color(0xFFA855F7),
                fill = Color(0xFF9333EA),
                onFill = Color(0xFFFFFFFF)
            )
            // Muted steel blue (the design system's info hue).
            STEEL_BLUE -> Values(
                marks = Color(0xFF7C9CC4),
                fill = Color(0xFF33618F),
                onFill = Color(0xFFFFFFFF)
            )
            // The monochrome drift the palettes shipped with, kept as a choice.
            MONOCHROME -> Values(
                marks = Color(0xFFFFFFFF),
                fill = Color(0xFFFFFFFF),
                onFill = Color(0xFF000000)
            )
        }

    private val lightValues: Values
        get() = when (this) {
            SIGNAL_GREEN -> Values(
                marks = Color(0xFF0B7A44),
                // Fill = marks colour (was #0E8A4C): white onFill reaches
                // WCAG AA at 5.41:1 instead of 4.41:1 (D8 contrast pass).
                fill = Color(0xFF0B7A44),
                onFill = Color(0xFFFFFFFF)
            )
            CYAN -> Values(
                // Marks darkened #0284C7 → #0369A1: accent text/links on
                // white were 4.10:1; now 5.93:1 (D8 contrast pass).
                marks = Color(0xFF0369A1),
                fill = Color(0xFF0369A1),
                onFill = Color(0xFFFFFFFF)
            )
            VIOLET -> Values(
                marks = Color(0xFF7E22CE),
                fill = Color(0xFF7E22CE),
                onFill = Color(0xFFFFFFFF)
            )
            STEEL_BLUE -> Values(
                marks = Color(0xFF33618F),
                fill = Color(0xFF33618F),
                onFill = Color(0xFFFFFFFF)
            )
            MONOCHROME -> Values(
                marks = Color(0xFF09090B),
                fill = Color(0xFF09090B),
                onFill = Color(0xFFFFFFFF)
            )
        }

    /** Accent values for the requested brightness mode. */
    fun values(dark: Boolean): Values = if (dark) darkValues else lightValues

    companion object {
        /** The default accent for installs that predate the picker. */
        val DEFAULT: AccentTheme = SIGNAL_GREEN

        /** Resolves a persisted id (an enum [name]) back to a preset. */
        fun fromId(id: String?): AccentTheme =
            entries.firstOrNull { it.name == id } ?: DEFAULT
    }
}
