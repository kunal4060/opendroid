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

    /**
     * Inbound share (feature C2): another app handed us text and/or images via
     * ACTION_SEND / ACTION_SEND_MULTIPLE. [parsed] holds the raw extras;
     * image streams are still content URIs at this point — the consumer copies
     * them into app-private storage before use (see SharedImageStore), because
     * the sender's read grant is temporary.
     */
    data class SharedContent(val parsed: ParsedShare) : ExternalLaunch
}
