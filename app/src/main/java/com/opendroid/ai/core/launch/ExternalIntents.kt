package com.opendroid.ai.core.launch

import android.content.Context
import android.content.Intent
import com.opendroid.ai.MainActivity

/**
 * Builds and reads the `Intent`s that carry an [ExternalLaunch] into
 * [MainActivity]. Used by the Glance widget (C1), the dynamic-shortcut
 * publisher (C1), and any future external surface, so every entry point
 * produces byte-identical intents that [MainActivity] parses the same way.
 *
 * The static shortcuts in `res/xml/shortcuts.xml` cannot call this code, but
 * they spell the same action/extras from [ExternalLaunchCodec] by hand.
 */
object ExternalIntents {

    /**
     * An intent that brings OpenDroid to the front performing [launch].
     *
     * `MainActivity` is `singleTask`, so when the app is already running this
     * lands in `onNewIntent` instead of stacking a second instance; the
     * flags make that true from widget/shortcut contexts too.
     */
    fun intent(context: Context, launch: ExternalLaunch): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ExternalLaunchCodec.ACTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ExternalLaunchCodec.EXTRA_KIND, ExternalLaunchCodec.kindOf(launch))
            ExternalLaunchCodec.extrasOf(launch).forEach { (key, value) -> putExtra(key, value) }
        }

    /** The launch an incoming intent asks for, or null when it carries none. */
    fun fromIntent(intent: Intent?): ExternalLaunch? {
        if (intent == null) return null
        val extras = mapOf(
            ExternalLaunchCodec.EXTRA_KIND to intent.getStringExtra(ExternalLaunchCodec.EXTRA_KIND),
            ExternalLaunchCodec.EXTRA_ROUTINE_ID to intent.getStringExtra(ExternalLaunchCodec.EXTRA_ROUTINE_ID),
            ExternalLaunchCodec.EXTRA_MACRO_ID to intent.getStringExtra(ExternalLaunchCodec.EXTRA_MACRO_ID)
        )
        return ExternalLaunchCodec.parse(intent.action, extras)
    }
}
