package com.opendroid.ai.core.llm

import com.opendroid.ai.data.db.entities.ModelEntity
import com.opendroid.ai.data.db.entities.ModelStatus
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.withActiveProvider
import com.opendroid.ai.data.models.withSelectedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSetupPolicyTest {

    private fun liteRtEntity(id: String, status: ModelStatus) = ModelEntity(
        id = id,
        name = id,
        version = "1.0.0",
        size = 0L,
        downloadUrl = "",
        localPath = "",
        status = status,
        downloadProgress = if (status == ModelStatus.READY) 100 else 0,
        lastUsed = 0L,
        installedAt = 0L
    )

    // ── Download fit gate ──────────────────────────────────────────────────

    @Test
    fun `download that exceeds free space does not fit`() {
        assertEquals(
            DownloadFit.DoesNotFit,
            ProviderSetupPolicy.assessDownloadFit(modelBytes = 2_000L, freeBytes = 1_000L)
        )
    }

    @Test
    fun `download leaving less than the safety margin warns`() {
        val free = ProviderSetupPolicy.MIN_FREE_AFTER_DOWNLOAD_BYTES + 500L
        assertEquals(
            DownloadFit.FitsWithLowSpaceWarning,
            ProviderSetupPolicy.assessDownloadFit(modelBytes = 600L, freeBytes = free)
        )
    }

    @Test
    fun `download with room to spare fits`() {
        val free = ProviderSetupPolicy.MIN_FREE_AFTER_DOWNLOAD_BYTES + 5_000L
        assertEquals(
            DownloadFit.Fits,
            ProviderSetupPolicy.assessDownloadFit(modelBytes = 600L, freeBytes = free)
        )
    }

    // ── canAnswer: the limited-state predicate ─────────────────────────────

    @Test
    fun `fresh install with default gemini and no key cannot answer`() {
        assertFalse(
            ProviderSetupPolicy.canAnswer(
                config = LLMConfig(),
                liteRtModels = emptyList(),
                aiCoreAvailable = false
            )
        )
    }

    @Test
    fun `gemini with a stored key can answer`() {
        val config = LLMConfig(apiKeys = mapOf("Google Gemini" to "key"))
        assertTrue(
            ProviderSetupPolicy.canAnswer(config, emptyList(), aiCoreAvailable = false)
        )
    }

    @Test
    fun `a key for a different provider does not make the active one answer`() {
        val config = LLMConfig(
            activeProvider = "Google Gemini",
            apiKeys = mapOf("OpenAI" to "key")
        )
        assertFalse(
            ProviderSetupPolicy.canAnswer(config, emptyList(), aiCoreAvailable = false)
        )
    }

    @Test
    fun `ollama needs its url`() {
        val withoutUrl = LLMConfig(activeProvider = "Ollama")
        assertFalse(
            ProviderSetupPolicy.canAnswer(withoutUrl, emptyList(), aiCoreAvailable = false)
        )
        val withUrl = withoutUrl.copy(ollamaUrl = "http://127.0.0.1:11434")
        assertTrue(
            ProviderSetupPolicy.canAnswer(withUrl, emptyList(), aiCoreAvailable = false)
        )
    }

    @Test
    fun `ai core selection answers only when ai core is available`() {
        val config = LLMConfig()
            .withActiveProvider(ProviderCatalog.ON_DEVICE)
            .withSelectedModel(ProviderCatalog.ON_DEVICE, "gemma-4-on-device")
        assertFalse(
            ProviderSetupPolicy.canAnswer(config, emptyList(), aiCoreAvailable = false)
        )
        assertTrue(
            ProviderSetupPolicy.canAnswer(config, emptyList(), aiCoreAvailable = true)
        )
    }

    @Test
    fun `litert selection answers when its model is ready`() {
        val spec = OnDeviceModelRegistry.recommendedFor(OnDeviceBackend.LITERT_LM)!!
        val config = LLMConfig()
            .withActiveProvider(ProviderCatalog.ON_DEVICE)
            .withSelectedModel(ProviderCatalog.ON_DEVICE, spec.id)
        assertFalse(
            ProviderSetupPolicy.canAnswer(
                config,
                listOf(liteRtEntity(spec.id, ModelStatus.NOT_DOWNLOADED)),
                aiCoreAvailable = false
            )
        )
        assertTrue(
            ProviderSetupPolicy.canAnswer(
                config,
                listOf(liteRtEntity(spec.id, ModelStatus.READY)),
                aiCoreAvailable = false
            )
        )
        // The hybrid provider falls back across backends, so AI Core alone is
        // also enough even while the LiteRT model is absent.
        assertTrue(
            ProviderSetupPolicy.canAnswer(config, emptyList(), aiCoreAvailable = true)
        )
    }
}
