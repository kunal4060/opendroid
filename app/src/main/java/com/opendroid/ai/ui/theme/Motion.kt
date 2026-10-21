package com.opendroid.ai.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * Reduce-motion state for the whole UI (D8 accessibility pass).
 *
 * The effective value is resolved once in [OpenDroidTheme] as:
 * the user's Settings → Accessibility → Reduce motion toggle **OR** the
 * system animator-duration scale being 0 (the user removed animations
 * system-wide). Continuous animations — the voice orb pulse, the thinking
 * dots and the voice waveform in Chat, the splash pulse — read this local
 * and render static frames instead of looping when it is true. One-shot
 * transitions (tab fades, dialog entries) are deliberately not gated:
 * they are brief and carry state-change information.
 */
val LocalReduceMotion = compositionLocalOf { false }

/** Access the effective reduce-motion state from any @Composable. */
object Motion {
    val reduceMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReduceMotion.current

    // ── Duration ladder (research-3-design-system.md §6.2, ms) ───────
    /** 100ms — press states, icon fill toggles, chip select. */
    const val durationMicro: Int = 100
    /** 150ms — tooltips, small fades, typing-dot cycle step. */
    const val durationShort: Int = 150
    /** 250ms — message arrival, plan-step expand, animateContentSize. */
    const val durationMedium: Int = 250
    /** 300ms — screen transitions, sheet entry. */
    const val durationStandard: Int = 300
    /** 400ms — large surfaces, onboarding page changes. */
    const val durationLong: Int = 400

    // Exit variants are always 0.75× the matching entry duration (§6.2).
    const val durationMicroExit: Int = 75
    const val durationShortExit: Int = 113
    const val durationMediumExit: Int = 188
    const val durationStandardExit: Int = 225
    const val durationLongExit: Int = 300

    // ── Easings (M3 emphasized family, §6.2) ──────────────────────────
    /** Emphasized: cubic-bezier(0.2, 0, 0, 1) — movement on screen. */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Emphasized-decelerate: cubic-bezier(0.05, 0.7, 0.1, 1) — entering. */
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Emphasized-accelerate: cubic-bezier(0.3, 0, 0.8, 0.15) — exiting. */
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Small-fade workhorse. */
    val fastOutSlowIn: Easing = FastOutSlowInEasing

    // ── Springs (§6.2) ────────────────────────────────────────────────
    const val springDefaultDampingRatio: Float = 1.0f
    const val springDefaultStiffness: Float = 1500f
    const val springGentleDampingRatio: Float = 0.85f
    const val springGentleStiffness: Float = 800f

    /** Default spring: spring(1.0, 1500) — layout/state changes, nav pill. */
    fun <T> springDefault(): SpringSpec<T> = spring(
        dampingRatio = springDefaultDampingRatio,
        stiffness = springDefaultStiffness
    )

    /** Gentle spring: spring(0.85, 800) — send-button morph, orb settle. */
    fun <T> springGentle(): SpringSpec<T> = spring(
        dampingRatio = springGentleDampingRatio,
        stiffness = springGentleStiffness
    )
}

/**
 * True when the system animator duration scale is 0 — animations have been
 * removed at the OS level (developer options or an accessibility setting).
 * Reading [Settings.Global] needs no permission. Any failure to read reads
 * as "not reduced" so a platform quirk can never freeze the UI's motion
 * state on.
 */
fun systemReduceMotionEnabled(context: Context): Boolean =
    try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } catch (e: Exception) {
        false
    }
