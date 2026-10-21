package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checklist A9: the storage/RAM fit policy, the filename quantisation heuristic,
 * and the catalogue's format/quant labels. All numbers are the real registry
 * values — the same `expectedSize` bytes ModelDownloadWorker enforces.
 */
class ModelFitPolicyTest {

    private fun gib(n: Long): Long = n * 1024L * 1024L * 1024L

    private fun spec(expectedSize: Long): OnDeviceModelSpec = OnDeviceModelSpec(
        id = "test-spec",
        displayName = "Test",
        family = "Test",
        sizeLabel = "1B",
        backend = OnDeviceBackend.LITERT_LM,
        expectedSize = expectedSize
    )

    // ── Storage verdicts ──────────────────────────────────────────────

    @Test
    fun `model larger than free space is blocked`() {
        val a = ModelFitPolicy.assess(
            modelBytes = gib(4), freeBytes = gib(2), totalStorageBytes = gib(64)
        )
        assertEquals(ModelFitVerdict.BLOCK, a.verdict)
        assertEquals(gib(4), a.modelBytes)
        assertEquals(gib(2), a.freeBytes)
        assertEquals(-gib(2), a.remainingAfterBytes)
    }

    @Test
    fun `exact fit is a warning not a block`() {
        val a = ModelFitPolicy.assess(
            modelBytes = gib(3), freeBytes = gib(3), totalStorageBytes = gib(64)
        )
        assertEquals(ModelFitVerdict.WARN, a.verdict)
        assertEquals(0L, a.remainingAfterBytes)
    }

    @Test
    fun `remaining below one GiB warns on a small volume`() {
        // 16 GiB volume: 5% is 0.8 GiB, so the 1 GiB floor is the binding margin.
        val a = ModelFitPolicy.assess(
            modelBytes = gib(7) / 2, freeBytes = gib(4), totalStorageBytes = gib(16)
        )
        assertEquals(ModelFitVerdict.WARN, a.verdict)
        assertEquals(gib(1) / 2, a.remainingAfterBytes)
    }

    @Test
    fun `five percent rule warns on a large volume even above one GiB remaining`() {
        // 512 GiB volume: margin is 25.6 GiB; 20 GiB remaining is above the 1 GiB
        // floor but below 5%, so it still warns.
        val a = ModelFitPolicy.assess(
            modelBytes = gib(20), freeBytes = gib(40), totalStorageBytes = gib(512)
        )
        assertEquals(ModelFitVerdict.WARN, a.verdict)
        assertEquals(gib(20), a.remainingAfterBytes)
    }

    @Test
    fun `comfortable fit is ok`() {
        val a = ModelFitPolicy.assess(
            modelBytes = gib(2), freeBytes = gib(30), totalStorageBytes = gib(128)
        )
        assertEquals(ModelFitVerdict.OK, a.verdict)
        assertEquals(gib(28), a.remainingAfterBytes)
    }

    @Test
    fun `zero byte model with space available is ok`() {
        val a = ModelFitPolicy.assess(
            modelBytes = 0L, freeBytes = gib(10), totalStorageBytes = gib(64)
        )
        assertEquals(ModelFitVerdict.OK, a.verdict)
    }

    // ── RAM verdict (surfaced, never reimplemented) ───────────────────

    @Test
    fun `ram insufficiency is surfaced without changing the storage verdict`() {
        val a = ModelFitPolicy.assess(
            modelBytes = gib(2), freeBytes = gib(30), totalStorageBytes = gib(128),
            requiredRamBytes = gib(6), totalRamBytes = gib(4)
        )
        assertEquals(ModelFitVerdict.OK, a.verdict)
        assertFalse(a.ramSufficient)
    }

    @Test
    fun `ram sufficient when device meets the requirement`() {
        val a = ModelFitPolicy.assess(
            modelBytes = gib(2), freeBytes = gib(30), totalStorageBytes = gib(128),
            requiredRamBytes = gib(6), totalRamBytes = gib(8)
        )
        assertTrue(a.ramSufficient)
    }

    @Test
    fun `unknown device ram is treated as sufficient`() {
        val a = ModelFitPolicy.assess(
            modelBytes = gib(2), freeBytes = gib(30), totalStorageBytes = gib(128),
            requiredRamBytes = gib(6), totalRamBytes = 0L
        )
        assertTrue(a.ramSufficient)
    }

    // ── Gate mapping ──────────────────────────────────────────────────

    @Test
    fun `gate maps verdicts to typed results`() {
        val blocked = ModelFitPolicy.assess(gib(4), gib(2), gib(64))
        val warned = ModelFitPolicy.assess(gib(3), gib(3), gib(64))
        val ok = ModelFitPolicy.assess(gib(2), gib(30), gib(128))

        val blockedGate = ModelFitPolicy.gate(blocked)
        assertTrue(blockedGate is ModelDownloadGate.Blocked)
        assertEquals(blocked, (blockedGate as ModelDownloadGate.Blocked).assessment)

        val warnGate = ModelFitPolicy.gate(warned)
        assertTrue(warnGate is ModelDownloadGate.NeedsConfirmation)
        assertEquals(warned, (warnGate as ModelDownloadGate.NeedsConfirmation).assessment)

        assertEquals(ModelDownloadGate.Proceed, ModelFitPolicy.gate(ok))
    }

    // ── Registry RAM heuristic (the one the policy surfaces) ─────────

    @Test
    fun `requiredRamBytes follows the registry thresholds`() {
        assertEquals(gib(8), OnDeviceModelRegistry.requiredRamBytes(spec(3_659_530_240L)))
        assertEquals(gib(8), OnDeviceModelRegistry.requiredRamBytes(spec(gib(3))))
        assertEquals(gib(6), OnDeviceModelRegistry.requiredRamBytes(spec(2_588_147_712L)))
        assertEquals(gib(4), OnDeviceModelRegistry.requiredRamBytes(spec(gib(1))))
        assertEquals(0L, OnDeviceModelRegistry.requiredRamBytes(spec(546_660_344L)))
        assertEquals(0L, OnDeviceModelRegistry.requiredRamBytes(spec(0L)))
    }

    // ── Quantisation labels ───────────────────────────────────────────

    @Test
    fun `quant label comes from filename tokens only`() {
        assertEquals("INT4", OnDeviceModelRegistry.quantLabelFromFilename("gemma-3n-E2B-it-int4.litertlm"))
        assertEquals("INT8", OnDeviceModelRegistry.quantLabelFromFilename("Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"))
        assertEquals("INT4", OnDeviceModelRegistry.quantLabelFromFilename("MODEL-INT4.LITERTLM"))
        assertEquals("", OnDeviceModelRegistry.quantLabelFromFilename("gemma-4-E2B-it.litertlm"))
        assertEquals("", OnDeviceModelRegistry.quantLabelFromFilename("model.task"))
    }

    @Test
    fun `custom spec labels quant only when the filename states it`() {
        val int4 = OnDeviceModelRegistry.customSpec(
            id = "custom-x-12345678", displayName = "x",
            modelFilename = "phi-int4.litertlm", expectedSize = 123L
        )
        assertEquals("LiteRT-LM", int4.formatLabel)
        assertEquals("INT4", int4.quantLabel)

        val unknown = OnDeviceModelRegistry.customSpec(
            id = "custom-y-12345678", displayName = "y",
            modelFilename = "phi.litertlm", expectedSize = 123L
        )
        assertEquals("", unknown.quantLabel)
    }

    @Test
    fun `catalogue carries explicit format and quant labels`() {
        OnDeviceModelRegistry.liteRTOnly.forEach { spec ->
            assertEquals("LiteRT-LM", spec.formatLabel)
            assertTrue("quant label missing for ${spec.id}", spec.quantLabel.isNotBlank())
        }
        assertEquals("INT4", OnDeviceModelRegistry.findById("gemma-3n-e2b-it-litert")?.quantLabel)
        assertEquals("INT4", OnDeviceModelRegistry.findById("gemma-3n-e4b-it-litert")?.quantLabel)
        assertEquals("INT8", OnDeviceModelRegistry.findById("qwen-2.5-0.5b-it-litert")?.quantLabel)
        assertEquals("Mixed 2/4/8-bit", OnDeviceModelRegistry.findById("gemma-4-e2b-it-litert")?.quantLabel)
        assertEquals("Mixed 2/4/8-bit", OnDeviceModelRegistry.findById("gemma-4-e4b-it-litert")?.quantLabel)

        // AI Core models are system-managed: no fake sizes, no quant claims.
        OnDeviceModelRegistry.aiCoreOnly.forEach { spec ->
            assertEquals("AI Core", spec.formatLabel)
            assertEquals(0L, spec.expectedSize)
            assertEquals("", spec.quantLabel)
        }
        // Every managed-downloadable model has a real, worker-enforced size.
        OnDeviceModelRegistry.allModels.filter { it.isManagedDownloadAvailable }.forEach { spec ->
            assertTrue("expectedSize missing for ${spec.id}", spec.expectedSize > 0L)
        }
    }
}
