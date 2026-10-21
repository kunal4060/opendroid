package com.opendroid.ai.core.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The router is the queue between MainActivity and the navigation layer:
 * these tests pin the two properties the queue exists for — identical
 * launches stay distinct (sequence numbers), and a consumer can only clear
 * the submission it actually handled.
 */
class ExternalIntentRouterTest {

    @Test
    fun `identical launches get distinct sequences`() {
        val router = ExternalIntentRouter()
        router.submit(ExternalLaunch.VoiceCommand)
        val first = router.pending.value
        router.submit(ExternalLaunch.VoiceCommand)
        val second = router.pending.value

        assertEquals(ExternalLaunch.VoiceCommand, first?.launch)
        assertEquals(ExternalLaunch.VoiceCommand, second?.launch)
        assertEquals(true, (second?.sequence ?: 0L) > (first?.sequence ?: 0L))
    }

    @Test
    fun `consume only clears the handled submission`() {
        val router = ExternalIntentRouter()
        router.submit(ExternalLaunch.NewChat)
        val staleSequence = router.pending.value!!.sequence
        router.submit(ExternalLaunch.MorningBriefing)

        // A slow consumer finishing the OLD launch must not eat the new one.
        router.consume(staleSequence)
        assertEquals(ExternalLaunch.MorningBriefing, router.pending.value?.launch)

        router.consume(router.pending.value!!.sequence)
        assertNull(router.pending.value)
    }
}
