package com.opendroid.ai.core.launch

import com.opendroid.ai.core.intent.ExternalLaunch

/**
 * The wire format of an [ExternalLaunch] inside an `Intent`.
 *
 * The sealed [ExternalLaunch] type itself lives in `core/intent` — the one
 * implementation shared by every external surface (C1 widget/shortcuts, C2
 * shares, C3 assistant role, C7 intent API) since the Wave-1 merge. This
 * codec covers only the launches that travel as flat intent extras: the
 * widget/shortcut kinds below. A share ([ExternalLaunch.SharedContent])
 * carries streams and parcels, never extras, so it has no codec form —
 * [kindOf] refuses it rather than inventing a lossy encoding.
 *
 * The same constants are referenced from `res/xml/shortcuts.xml` (the static
 * launcher shortcuts), so the values here and in that file must move together.
 * The mapping is deliberately free of Android framework classes, which keeps
 * it unit-testable on the JVM.
 */
object ExternalLaunchCodec {

    /** Intent action carried by every external-launch intent. */
    const val ACTION = "com.opendroid.ai.action.EXTERNAL_LAUNCH"

    const val EXTRA_KIND = "com.opendroid.ai.extra.LAUNCH_KIND"
    const val EXTRA_ROUTINE_ID = "com.opendroid.ai.extra.ROUTINE_ID"
    const val EXTRA_MACRO_ID = "com.opendroid.ai.extra.MACRO_ID"

    const val KIND_OPEN_CHAT = "open_chat"
    const val KIND_VOICE = "voice"
    const val KIND_NEW_CHAT = "new_chat"
    const val KIND_MORNING_BRIEFING = "morning_briefing"
    const val KIND_OPEN_ROUTINES = "open_routines"
    const val KIND_RUN_ROUTINE = "run_routine"
    const val KIND_RUN_MACRO = "run_macro"

    /** The `kind` discriminator string for [launch]. */
    fun kindOf(launch: ExternalLaunch): String = when (launch) {
        ExternalLaunch.OpenChat -> KIND_OPEN_CHAT
        ExternalLaunch.VoiceCommand -> KIND_VOICE
        ExternalLaunch.NewChat -> KIND_NEW_CHAT
        ExternalLaunch.MorningBriefing -> KIND_MORNING_BRIEFING
        ExternalLaunch.OpenRoutines -> KIND_OPEN_ROUTINES
        is ExternalLaunch.RunRoutine -> KIND_RUN_ROUTINE
        is ExternalLaunch.RunMacro -> KIND_RUN_MACRO
        is ExternalLaunch.SharedContent -> throw IllegalArgumentException(
            "SharedContent travels as an ACTION_SEND intent, not an external-launch intent"
        )
    }

    /** The extras [launch] needs beyond the kind discriminator. */
    fun extrasOf(launch: ExternalLaunch): Map<String, String> = when (launch) {
        is ExternalLaunch.RunRoutine -> mapOf(EXTRA_ROUTINE_ID to launch.routineId)
        is ExternalLaunch.RunMacro -> mapOf(EXTRA_MACRO_ID to launch.macroId)
        else -> emptyMap()
    }

    /**
     * Rebuilds a launch from an intent's action and extras, or returns null
     * when the intent is not an external launch at all (e.g. the plain
     * launcher MAIN intent), or when a parameterised kind is missing its id —
     * a malformed external intent must degrade to "not ours", never crash.
     */
    fun parse(action: String?, extras: Map<String, String?>): ExternalLaunch? {
        if (action != ACTION) return null
        return when (extras[EXTRA_KIND]) {
            KIND_OPEN_CHAT -> ExternalLaunch.OpenChat
            KIND_VOICE -> ExternalLaunch.VoiceCommand
            KIND_NEW_CHAT -> ExternalLaunch.NewChat
            KIND_MORNING_BRIEFING -> ExternalLaunch.MorningBriefing
            KIND_OPEN_ROUTINES -> ExternalLaunch.OpenRoutines
            KIND_RUN_ROUTINE -> extras[EXTRA_ROUTINE_ID]
                ?.takeIf { it.isNotBlank() }
                ?.let { ExternalLaunch.RunRoutine(it) }
            KIND_RUN_MACRO -> extras[EXTRA_MACRO_ID]
                ?.takeIf { it.isNotBlank() }
                ?.let { ExternalLaunch.RunMacro(it) }
            else -> null
        }
    }
}
