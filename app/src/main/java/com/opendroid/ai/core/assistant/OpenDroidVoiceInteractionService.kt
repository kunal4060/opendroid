package com.opendroid.ai.core.assistant

import android.service.voice.VoiceInteractionService

/**
 * C3 (Tier 2) — the entry point the system binds when OpenDroid holds
 * the assistant role.
 *
 * Holding ROLE_ASSISTANT makes the system start this service and route
 * assistant invocations (home long-press, power-button long-press,
 * corner swipe — OEM-dependent) to the session created by
 * [OpenDroidVoiceInteractionSessionService]. The service itself is
 * intentionally thin: all behaviour lives in the session + controller.
 * Declared in the manifest with BIND_VOICE_INTERACTION and the
 * `android.voice_interaction` meta-data (res/xml/voice_interaction_service.xml).
 */
class OpenDroidVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // The system considers the assistant ready at this point; sessions
        // are created per invocation by the session service, so there is
        // no warm-up state to prepare here.
    }
}
