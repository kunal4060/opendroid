package com.opendroid.ai.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConversationControllerTest {

    private fun controller() = VoiceConversationController()

    @Test
    fun `start opens listening exactly once`() {
        val c = controller()
        assertEquals(listOf(VoiceSessionEffect.BeginListening), c.start())
        assertEquals(VoiceConversationController.State.LISTENING, c.state)
        assertTrue(c.isActive)
        assertEquals(VoiceSessionPhase.LISTENING, c.phase)
        // Second start is a no-op while active.
        assertEquals(emptyList<VoiceSessionEffect>(), c.start())
    }

    @Test
    fun `utterance submits to agent and enters thinking`() {
        val c = controller()
        c.start()
        val effects = c.onUtterance("  what's the weather tomorrow? ")
        assertEquals(
            listOf(
                VoiceSessionEffect.CancelListening,
                VoiceSessionEffect.SubmitQuery("what's the weather tomorrow?")
            ),
            effects
        )
        assertEquals(VoiceConversationController.State.THINKING, c.state)
    }

    @Test
    fun `full turn - speech finished relistens and counts the turn`() {
        val c = controller()
        c.start()
        c.onUtterance("first question")
        assertEquals(emptyList<VoiceSessionEffect>(), c.onSpeechStarted())
        assertEquals(VoiceConversationController.State.SPEAKING, c.state)
        assertEquals(listOf(VoiceSessionEffect.BeginListening), c.onSpeechFinished())
        assertEquals(VoiceConversationController.State.LISTENING, c.state)
        assertEquals(1, c.turnsCompleted)
        // Second turn works off the same session (context carries in the agent).
        c.onUtterance("and in the evening?")
        assertEquals(VoiceConversationController.State.THINKING, c.state)
    }

    @Test
    fun `turn that settles without speech relistens`() {
        val c = controller()
        c.start()
        c.onUtterance("do the thing")
        assertEquals(listOf(VoiceSessionEffect.BeginListening), c.onAgentTurnSettled())
        assertEquals(VoiceConversationController.State.LISTENING, c.state)
        assertEquals(1, c.turnsCompleted)
    }

    @Test
    fun `end phrase closes the session with a farewell`() {
        val c = controller()
        c.start()
        val effects = c.onUtterance("that's all")
        assertEquals(VoiceConversationController.State.OFF, c.state)
        assertFalse(c.isActive)
        assertNull(c.phase)
        val end = effects.filterIsInstance<VoiceSessionEffect.EndSession>().single()
        assertEquals(VoiceSessionEndReason.END_PHRASE, end.reason)
        assertEquals(
            VoiceConversationController.Config().endPhraseFarewell, end.farewell
        )
    }

    @Test
    fun `command-like requests are submitted, not treated as end phrases`() {
        val c = controller()
        c.start()
        val effects = c.onUtterance("stop the timer")
        assertTrue(effects.any { it is VoiceSessionEffect.SubmitQuery })
        assertEquals(VoiceConversationController.State.THINKING, c.state)
    }

    @Test
    fun `silence ladder - silent retry, spoken prompt, then polite end`() {
        val c = controller()
        c.start()
        // Miss 1: silent retry.
        assertEquals(listOf(VoiceSessionEffect.BeginListening), c.onListenTimeout())
        assertEquals(VoiceConversationController.State.LISTENING, c.state)
        // Miss 2: spoken still-listening prompt.
        val prompt = c.onListenTimeout()
        assertEquals(
            listOf(VoiceSessionEffect.SpeakText(VoiceConversationController.Config().stillListeningPrompt)),
            prompt
        )
        assertEquals(VoiceConversationController.State.SPEAKING, c.state)
        // Prompt speech completes -> listening resumes, no turn counted.
        assertEquals(listOf(VoiceSessionEffect.BeginListening), c.onSpeechFinished())
        assertEquals(0, c.turnsCompleted)
        // Miss 3 (after the prompt was already given): session ends politely.
        val end = c.onListenTimeout()
        assertEquals(VoiceConversationController.State.OFF, c.state)
        val endEffect = end.filterIsInstance<VoiceSessionEffect.EndSession>().single()
        assertEquals(VoiceSessionEndReason.SILENCE, endEffect.reason)
    }

    @Test
    fun `successful utterance resets the silence ladder`() {
        val c = controller()
        c.start()
        c.onListenTimeout() // miss 1
        c.onUtterance("a question")
        c.onSpeechStarted()
        c.onSpeechFinished() // back to listening, ladder reset
        // Next miss is a silent retry again, not the spoken prompt.
        assertEquals(listOf(VoiceSessionEffect.BeginListening), c.onListenTimeout())
    }

    @Test
    fun `fatal listen failure ends session, non-fatal joins the ladder`() {
        val fatal = controller()
        fatal.start()
        val effects = fatal.onListenFailure(fatal = true)
        assertEquals(VoiceConversationController.State.OFF, fatal.state)
        assertEquals(
            VoiceSessionEndReason.LISTEN_UNAVAILABLE,
            effects.filterIsInstance<VoiceSessionEffect.EndSession>().single().reason
        )

        val nonFatal = controller()
        nonFatal.start()
        assertEquals(listOf(VoiceSessionEffect.BeginListening), nonFatal.onListenFailure(fatal = false))
        assertEquals(VoiceConversationController.State.LISTENING, nonFatal.state)
    }

    @Test
    fun `barge-in stops speech and listens, only while speaking`() {
        val c = controller()
        c.start()
        // Not speaking yet: no-op.
        assertEquals(emptyList<VoiceSessionEffect>(), c.onBargeIn())
        c.onUtterance("question")
        c.onSpeechStarted()
        val effects = c.onBargeIn()
        assertEquals(
            listOf(VoiceSessionEffect.StopSpeaking, VoiceSessionEffect.BeginListening),
            effects
        )
        assertEquals(VoiceConversationController.State.LISTENING, c.state)
        // The interrupted reply does not count as a completed turn.
        assertEquals(0, c.turnsCompleted)
    }

    @Test
    fun `approval flow - proposed, resolved back to thinking`() {
        val c = controller()
        c.start()
        c.onUtterance("book a cab")
        c.onPlanProposed()
        assertEquals(VoiceConversationController.State.AWAITING_APPROVAL, c.state)
        assertEquals(VoiceSessionPhase.AWAITING_APPROVAL, c.phase)
        assertEquals(emptyList<VoiceSessionEffect>(), c.onApprovalResolved())
        assertEquals(VoiceConversationController.State.THINKING, c.state)
        // Execution summary speech then completes the turn.
        c.onSpeechStarted()
        assertEquals(listOf(VoiceSessionEffect.BeginListening), c.onSpeechFinished())
        assertEquals(1, c.turnsCompleted)
    }

    @Test
    fun `approval unresolved ends the voice session without touching the plan`() {
        val c = controller()
        c.start()
        c.onUtterance("book a cab")
        c.onPlanProposed()
        val effects = c.onApprovalUnresolved()
        assertEquals(VoiceConversationController.State.OFF, c.state)
        val end = effects.filterIsInstance<VoiceSessionEffect.EndSession>().single()
        assertEquals(VoiceSessionEndReason.APPROVAL_UNRESOLVED, end.reason)
        // No farewell speech over a pending approval prompt.
        assertNull(end.farewell)
    }

    @Test
    fun `turn failure speaks once and ends - no retry loop`() {
        val c = controller()
        c.start()
        c.onUtterance("question")
        val effects = c.onTurnFailed()
        assertEquals(VoiceConversationController.State.OFF, c.state)
        assertEquals(
            VoiceSessionEndReason.TURN_ERROR,
            effects.filterIsInstance<VoiceSessionEffect.EndSession>().single().reason
        )
        // Once off, every event is inert.
        assertEquals(emptyList<VoiceSessionEffect>(), c.onUtterance("hello again"))
        assertEquals(emptyList<VoiceSessionEffect>(), c.onListenTimeout())
        assertEquals(emptyList<VoiceSessionEffect>(), c.onSpeechFinished())
    }

    @Test
    fun `stop request ends from any active state`() {
        val listening = controller()
        listening.start()
        val e1 = listening.onStopRequested()
        assertEquals(VoiceConversationController.State.OFF, listening.state)
        assertEquals(
            VoiceSessionEndReason.USER_STOP,
            e1.filterIsInstance<VoiceSessionEffect.EndSession>().single().reason
        )
        assertTrue(e1.contains(VoiceSessionEffect.StopSpeaking))
        assertTrue(e1.contains(VoiceSessionEffect.CancelListening))

        val speaking = controller()
        speaking.start()
        speaking.onUtterance("q")
        speaking.onSpeechStarted()
        speaking.onStopRequested()
        assertEquals(VoiceConversationController.State.OFF, speaking.state)

        // Inert when already off.
        assertEquals(emptyList<VoiceSessionEffect>(), speaking.onStopRequested())
    }

    @Test
    fun `session cap and audio focus loss end the session`() {
        val capped = controller()
        capped.start()
        val capEffects = capped.onSessionCapReached()
        assertEquals(
            VoiceSessionEndReason.SESSION_CAP,
            capEffects.filterIsInstance<VoiceSessionEffect.EndSession>().single().reason
        )

        val focus = controller()
        focus.start()
        focus.onUtterance("q")
        val focusEffects = focus.onAudioFocusLost()
        val end = focusEffects.filterIsInstance<VoiceSessionEffect.EndSession>().single()
        assertEquals(VoiceSessionEndReason.AUDIO_FOCUS_LOST, end.reason)
        // No farewell — we no longer own the audio output.
        assertNull(end.farewell)
    }

    @Test
    fun `blank utterance counts as a listen miss`() {
        val c = controller()
        c.start()
        assertEquals(listOf(VoiceSessionEffect.BeginListening), c.onUtterance("   "))
        assertEquals(VoiceConversationController.State.LISTENING, c.state)
    }

    @Test
    fun `events in wrong states are ignored`() {
        val c = controller()
        // OFF: everything inert except start.
        assertEquals(emptyList<VoiceSessionEffect>(), c.onSpeechStarted())
        assertEquals(emptyList<VoiceSessionEffect>(), c.onPlanProposed())
        assertEquals(emptyList<VoiceSessionEffect>(), c.onApprovalResolved())
        c.start()
        // LISTENING: speech/approval events are inert.
        assertEquals(emptyList<VoiceSessionEffect>(), c.onSpeechFinished())
        assertEquals(emptyList<VoiceSessionEffect>(), c.onAgentTurnSettled())
        assertEquals(emptyList<VoiceSessionEffect>(), c.onApprovalUnresolved())
    }
}
