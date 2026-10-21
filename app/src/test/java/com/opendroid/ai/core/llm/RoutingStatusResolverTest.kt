package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingStatusResolverTest {

    private fun resolve(
        activeProvider: String = "Google Gemini",
        fallbackProviders: List<String> = emptyList(),
        isOnline: Boolean = true,
        onDeviceAvailable: Boolean = false,
        onDeviceBackendName: String? = null,
        lastAnsweredBy: String? = null
    ): RoutingStatus = RoutingStatusResolver.resolve(
        activeProvider = activeProvider,
        fallbackProviders = fallbackProviders,
        isOnline = isOnline,
        onDeviceAvailable = onDeviceAvailable,
        onDeviceBackendName = onDeviceBackendName,
        lastAnsweredBy = lastAnsweredBy
    )

    @Test
    fun `online with cloud active shows the cloud provider`() {
        assertEquals(
            RoutingStatus.Cloud("Google Gemini"),
            resolve()
        )
    }

    @Test
    fun `online with on-device active shows the serving backend`() {
        assertEquals(
            RoutingStatus.OnDevice("LiteRT-LM (On-device)"),
            resolve(
                activeProvider = "On-Device AI",
                onDeviceAvailable = true,
                onDeviceBackendName = "LiteRT-LM (On-device)"
            )
        )
    }

    @Test
    fun `failover answer from on-device fallback flips the badge to on-device`() {
        assertEquals(
            RoutingStatus.OnDevice("Gemma 4 (On-device)"),
            resolve(
                fallbackProviders = listOf("On-Device AI"),
                onDeviceAvailable = true,
                onDeviceBackendName = "Gemma 4 (On-device)",
                lastAnsweredBy = "On-Device AI"
            )
        )
    }

    @Test
    fun `failover answer from another cloud fallback names that provider`() {
        assertEquals(
            RoutingStatus.Cloud("OpenAI"),
            resolve(
                fallbackProviders = listOf("OpenAI"),
                lastAnsweredBy = "OpenAI"
            )
        )
    }

    @Test
    fun `answer from the active provider itself keeps the configured cloud state`() {
        assertEquals(
            RoutingStatus.Cloud("Google Gemini"),
            resolve(lastAnsweredBy = "Google Gemini")
        )
    }

    @Test
    fun `stale last-served record from a removed provider is ignored`() {
        assertEquals(
            RoutingStatus.Cloud("Google Gemini"),
            resolve(lastAnsweredBy = "Cohere")
        )
    }

    @Test
    fun `offline with cloud active and on-device ready will answer on-device`() {
        assertEquals(
            RoutingStatus.OfflineWillUseOnDevice("LiteRT-LM (On-device)"),
            resolve(
                isOnline = false,
                onDeviceAvailable = true,
                onDeviceBackendName = "LiteRT-LM (On-device)"
            )
        )
    }

    @Test
    fun `offline with cloud active and no on-device route is no-route`() {
        assertEquals(
            RoutingStatus.OfflineNoRoute,
            resolve(isOnline = false, onDeviceAvailable = false)
        )
    }

    @Test
    fun `offline with on-device active and ready stays on-device`() {
        assertEquals(
            RoutingStatus.OnDevice("Gemma 4 (On-device)"),
            resolve(
                activeProvider = "On-Device AI",
                isOnline = false,
                onDeviceAvailable = true,
                onDeviceBackendName = "Gemma 4 (On-device)"
            )
        )
    }

    @Test
    fun `offline with on-device active but no backend ready is no-route`() {
        assertEquals(
            RoutingStatus.OfflineNoRoute,
            resolve(
                activeProvider = "On-Device AI",
                isOnline = false,
                onDeviceAvailable = false
            )
        )
    }

    @Test
    fun `offline beats a stale cloud last-served record`() {
        assertEquals(
            RoutingStatus.OfflineNoRoute,
            resolve(
                isOnline = false,
                onDeviceAvailable = false,
                lastAnsweredBy = "Google Gemini"
            )
        )
    }

    @Test
    fun `missing backend name falls back to a generic local label`() {
        assertEquals(
            RoutingStatus.OnDevice("Local model"),
            resolve(
                activeProvider = "On-Device AI",
                onDeviceAvailable = true,
                onDeviceBackendName = "None available"
            )
        )
    }

    @Test
    fun `legacy on-device provider names are recognised as on-device`() {
        assertEquals(
            RoutingStatus.OnDevice("Gemma 4 (On-device)"),
            resolve(
                activeProvider = "Gemma 4 (On-device)",
                onDeviceAvailable = true,
                onDeviceBackendName = "Gemma 4 (On-device)"
            )
        )
    }
}
