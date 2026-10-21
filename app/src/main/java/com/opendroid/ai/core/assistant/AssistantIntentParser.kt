package com.opendroid.ai.core.assistant

import android.app.SearchManager
import android.content.Intent

/**
 * C3 — parses an activity launch into "was this an assistant invocation,
 * and did it carry a spoken/typed command for the agent?".
 *
 * Assistant invocations reach the app two ways:
 *  - the Tier-1 [AssistActivity] handling `Intent.ACTION_ASSIST`, which
 *    forwards into MainActivity marked with [EXTRA_FROM_ASSISTANT];
 *  - any launcher/peer that starts MainActivity with a query extra.
 *
 * The parse itself is pure (action string + a flat extras map) so it is
 * unit-testable without the framework; [extrasOf] does the Android-side
 * extraction of just the keys the parser understands.
 */
object AssistantIntentParser {

    /** Our own query extra: the command text to hand to the agent. */
    const val EXTRA_ASSIST_QUERY: String = "com.opendroid.ai.extra.ASSIST_QUERY"

    /** Marker AssistActivity sets so MainActivity knows the launch came via ACTION_ASSIST. */
    const val EXTRA_FROM_ASSISTANT: String = "com.opendroid.ai.extra.FROM_ASSISTANT"

    data class ParsedLaunch(
        val isAssistantInvocation: Boolean,
        /** The command carried by the launch, or null when the user still has to speak/type it. */
        val query: String?
    )

    fun parse(action: String?, extras: Map<String, Any?>): ParsedLaunch {
        val isAssistantInvocation =
            action == Intent.ACTION_ASSIST || extras[EXTRA_FROM_ASSISTANT] == true

        if (!isAssistantInvocation) return ParsedLaunch(false, null)

        // Priority: our explicit extra first, then the standard search
        // query key, then EXTRA_TEXT (several launchers and automation
        // apps deliver the command as plain text).
        val query = sequenceOf(
            extras[EXTRA_ASSIST_QUERY] as? String,
            extras[SearchManager.QUERY] as? String,
            extras[Intent.EXTRA_TEXT] as? String
        ).map { it?.trim() }
            .firstOrNull { !it.isNullOrEmpty() }

        return ParsedLaunch(isAssistantInvocation = true, query = query)
    }

    /** Extracts only the keys [parse] understands from a real intent. */
    fun extrasOf(intent: Intent?): Map<String, Any?> {
        if (intent == null) return emptyMap()
        return mapOf(
            EXTRA_ASSIST_QUERY to intent.getStringExtra(EXTRA_ASSIST_QUERY),
            EXTRA_FROM_ASSISTANT to intent.getBooleanExtra(EXTRA_FROM_ASSISTANT, false),
            SearchManager.QUERY to intent.getStringExtra(SearchManager.QUERY),
            Intent.EXTRA_TEXT to intent.getStringExtra(Intent.EXTRA_TEXT)
        )
    }
}
