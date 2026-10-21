package com.opendroid.ai.core.intent

import com.opendroid.ai.core.share.ParsedShare

/**
 * A typed external launch request: something outside the app's own UI (a share
 * sheet, a widget, the assistant role, an external intent API) asked OpenDroid
 * to open with intent.
 *
 * This is the shared plumbing the Batch C system features ride on — C1
 * (widget/shortcuts), C3 (assistant role) and C7 (intent API) add their own
 * variants here instead of inventing parallel delivery paths. MainActivity is
 * the single producer; the UI layer is the single consumer, and consumption
 * only happens once the app-lock gate (A5) and the splash/onboarding routing
 * have resolved, so an external launch can never render content behind the
 * lock or fight the entry flow.
 */
sealed interface ExternalLaunch {

    /** Plain app open on the chat tab (widget body tap, C1). */
    data object OpenChat : ExternalLaunch

    /** Open chat and immediately start voice input (C1). */
    data object VoiceCommand : ExternalLaunch

    /** Open chat on a brand-new, empty session (C1). */
    data object NewChat : ExternalLaunch

    /** Open chat and surface a freshly generated morning briefing in it (C1). */
    data object MorningBriefing : ExternalLaunch

    /** Open the Routines screen (C1). */
    data object OpenRoutines : ExternalLaunch

    /** Execute the routine with this id headlessly, through the real engine (C1). */
    data class RunRoutine(val routineId: String) : ExternalLaunch

    /** Execute the macro with this id headlessly, through the step executor (C1). */
    data class RunMacro(val macroId: String) : ExternalLaunch

    /**
     * Inbound share (feature C2): another app handed us text and/or images via
     * ACTION_SEND / ACTION_SEND_MULTIPLE. [parsed] holds the raw extras;
     * image streams are still content URIs at this point — the consumer copies
     * them into app-private storage before use (see SharedImageStore), because
     * the sender's read grant is temporary.
     */
    data class SharedContent(val parsed: ParsedShare) : ExternalLaunch
}
