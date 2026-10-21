package com.opendroid.ai.core.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * C3 (Tier 2) — factory the system uses to create one
 * [OpenDroidAssistantSession] per assistant invocation. Named by the
 * `sessionService` attribute of res/xml/voice_interaction_service.xml.
 */
class OpenDroidVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return OpenDroidAssistantSession(this)
    }
}
