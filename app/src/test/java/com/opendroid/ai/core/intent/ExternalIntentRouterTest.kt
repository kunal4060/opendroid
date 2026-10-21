package com.opendroid.ai.core.intent

import com.opendroid.ai.core.share.ParsedShare
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalIntentRouterTest {

    private fun share(text: String?) = ExternalLaunch.SharedContent(
        ParsedShare(text = text, subject = null, streamUris = emptyList(), declaredMimeType = "text/plain")
    )

    @Test
    fun `published launch is replayed to a late collector`() = runBlocking {
        val router = ExternalIntentRouter()
        val launch = share("hello")
        router.publish(launch)
        val received = withTimeout(1_000) { router.launches.first() }
        assertSame(launch, received)
        assertSame(launch, router.peek())
    }

    @Test
    fun `a launch can be claimed exactly once`() {
        val router = ExternalIntentRouter()
        val launch = share("once")
        router.publish(launch)
        assertTrue(router.claim(launch))
        assertFalse(router.claim(launch))
    }

    @Test
    fun `an identical second share is a new launch and claimable`() {
        val router = ExternalIntentRouter()
        val first = share("same text")
        val second = share("same text")
        assertEquals(first, second) // equal by value…
        router.publish(first)
        assertTrue(router.claim(first))
        router.publish(second)
        assertTrue(router.claim(second)) // …but a distinct instance, so claimable
    }

    @Test
    fun `release makes a launch claimable again`() {
        val router = ExternalIntentRouter()
        val launch = share("retry me")
        router.publish(launch)
        assertTrue(router.claim(launch))
        router.releaseClaim(launch)
        assertTrue(router.claim(launch))
    }
}
