package com.opendroid.ai.ui.theme

import android.content.Context
import android.provider.Settings
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
