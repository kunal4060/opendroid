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
    //
    // The verdicts come from ModelFitPolicy — the same policy the download
    // funnel enforces — with margin max(1 GiB, 5% of total storage). These
    // tests pin that unification: the old onboarding-only flat 1,000,000,000
    // byte gate disagreed with the funnel in both directions (F2).

    private fun gib(n: Long): Long = n * 1024L * 1024L * 1024L

    @Test
    fun `download that exceeds free space does not fit`() {
        assertEquals(
            DownloadFit.DoesNotFit,
            ProviderSetupPolicy.assessDownloadFit(
                modelBytes = 2_000L, freeBytes = 1_000L, totalStorageBytes = gib(16)
            )
        )
    }

    @Test
    fun `download leaving less than the safety margin warns`() {
        val free = ModelFitPolicy.MIN_SAFE_REMAINING_BYTES + 500L
        assertEquals(
            DownloadFit.FitsWithLowSpaceWarning,
            ProviderSetupPolicy.assessDownloadFit(
                modelBytes = 600L, freeBytes = free, totalStorageBytes = gib(8)
            )
        )
    }

    @Test
    fun `download with room to spare fits`() {
        val free = ModelFitPolicy.MIN_SAFE_REMAINING_BYTES + 5_000L
        assertEquals(
            DownloadFit.Fits,
            ProviderSetupPolicy.assessDownloadFit(
                modelBytes = 600L, freeBytes = free, totalStorageBytes = gib(8)
            )
        )
    }

    @Test
    fun `download leaving exactly the safety margin fits`() {
        val free = ModelFitPolicy.MIN_SAFE_REMAINING_BYTES + 600L
        assertEquals(
            DownloadFit.Fits,
            ProviderSetupPolicy.assessDownloadFit(
                modelBytes = 600L, freeBytes = free, totalStorageBytes = gib(8)
            )
        )
    }

    @Test
    fun `remaining between the old flat gate and one GiB now warns`() {
        // Regression (F2): the old onboarding gate used a flat 1,000,000,000
        // bytes, so 1.05 GB remaining read as "Fits" here while the funnel's
        // 1 GiB margin refused the same download as unconfirmed-tight. On a
        // 16 GiB volume the 1 GiB floor binds (5% is only 0.8 GiB).
        val free = 600L + 1_050_000_000L
        assertEquals(
            DownloadFit.FitsWithLowSpaceWarning,
            ProviderSetupPolicy.assessDownloadFit(
                modelBytes = 600L, freeBytes = free, totalStorageBytes = gib(16)
            )
        )
    }

    @Test
    fun `five percent margin warns on a large volume above one GiB remaining`() {
        // Regression (F2), other direction: on a 512 GiB volume the shared
        // margin is 25.6 GiB, so 20 GiB remaining warns — the old flat gate
        // called this "Fits" and the funnel then refused it.
        val free = gib(20) + 600L
        assertEquals(
            DownloadFit.FitsWithLowSpaceWarning,
            ProviderSetupPolicy.assessDownloadFit(
                modelBytes = 600L, freeBytes = free, totalStorageBytes = gib(512)
            )
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
