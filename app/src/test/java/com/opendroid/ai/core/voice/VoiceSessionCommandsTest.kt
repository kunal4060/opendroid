package com.opendroid.ai.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionCommandsTest {

    @Test
    fun `bare end phrases match`() {
        for (u in listOf(
            "stop", "Stop.", "that's all", "That is all", "goodbye", "good bye",
            "bye", "never mind", "nevermind", "end session", "I'm done",
            "we're done", "done", "cancel"
        )) {
            assertTrue(u, VoiceSessionCommands.isEndPhrase(u))
        }
    }

    @Test
    fun `politeness wrappers still match`() {
        assertTrue(VoiceSessionCommands.isEndPhrase("please stop"))
        assertTrue(VoiceSessionCommands.isEndPhrase("that's all, thanks"))
        assertTrue(VoiceSessionCommands.isEndPhrase("stop thank you"))
    }

    @Test
    fun `real requests containing end words do not match`() {
        for (u in listOf(
            "stop the timer",
            "cancel my 3pm meeting",
            "set a timer and stop it after ten minutes",
            "what does done mean",
            "goodbye songs by queen",
            "never mind the weather, just book it",
            ""
        )) {
            assertFalse(u, VoiceSessionCommands.isEndPhrase(u))
        }
    }
}
