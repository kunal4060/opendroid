package com.opendroid.ai.core.launch

/**
 * A typed request that arrives from outside the UI: a home-screen widget tap
 * (C1), a launcher shortcut (C1), a share-sheet intent (C2), the assistant
 * role (C3) or an automation intent (C7).
 *
 * Every external surface funnels into this one type instead of inventing its
 * own extras contract, so the navigation layer has exactly one place that
 * decides what an outside request means. The type is deliberately free of
 * Android framework classes: [ExternalLaunchCodec] converts to and from the
 * flat (action, extras) representation an `Intent` carries, which keeps the
 * whole mapping unit-testable on the JVM.
 */
sealed interface ExternalLaunch {

    /** Plain app open on the chat tab (widget body tap). */
    data object OpenChat : ExternalLaunch

    /** Open chat and immediately start voice input. */
    data object VoiceCommand : ExternalLaunch

    /** Open chat on a brand-new, empty session. */
    data object NewChat : ExternalLaunch

    /** Open chat and surface a freshly generated morning briefing in it. */
    data object MorningBriefing : ExternalLaunch

    /** Open the Routines screen. */
    data object OpenRoutines : ExternalLaunch

    /** Execute the routine with this id headlessly, through the real engine. */
    data class RunRoutine(val routineId: String) : ExternalLaunch

    /** Execute the macro with this id headlessly, through the step executor. */
    data class RunMacro(val macroId: String) : ExternalLaunch
}

/**
 * The wire format of an [ExternalLaunch] inside an `Intent`.
 *
 * The same constants are referenced from `res/xml/shortcuts.xml` (the static
 * launcher shortcuts), so the values here and in that file must move together.
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
