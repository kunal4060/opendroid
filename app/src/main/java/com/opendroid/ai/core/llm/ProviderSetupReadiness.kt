package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.providers.GemmaProvider
import com.opendroid.ai.data.db.entities.ModelEntity
import com.opendroid.ai.data.db.entities.ModelStatus
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.selectedModelFor
import com.opendroid.ai.data.repository.ModelRepository
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of the A9-style storage fit check for a first-run on-device download. */
sealed interface DownloadFit {
    /** The download fits and leaves at least the safety margin free. */
    data object Fits : DownloadFit

    /** The download fits, but would leave less than the safety margin free. */
    data object FitsWithLowSpaceWarning : DownloadFit

    /** The model file is larger than the free space on the device. */
    data object DoesNotFit : DownloadFit
}

/**
 * Pure setup-state policy shared by onboarding (A10) and the Chat limited-state
 * banner, so both surfaces agree on what "can answer" means.
 */
object ProviderSetupPolicy {

    /**
     * Fit gate for a first-run download: compares the model's real
     * [OnDeviceModelSpec.expectedSize] (the same bytes the download worker
     * enforces) against the device's actual free space, instead of steering a
     * new user into a download that cannot land.
     *
     * The verdict is delegated to [ModelFitPolicy] — the single policy the
     * [ModelRepository] download funnel also enforces — so the margin this
     * surface warns about is exactly the margin the funnel gates on
     * (max(1 GiB, 5% of total storage)), and the two can never disagree about
     * whether a download fits, warns, or is blocked.
     */
    fun assessDownloadFit(
        modelBytes: Long,
        freeBytes: Long,
        totalStorageBytes: Long
    ): DownloadFit = when (
        ModelFitPolicy.assess(
            modelBytes = modelBytes,
            freeBytes = freeBytes,
            totalStorageBytes = totalStorageBytes
        ).verdict
    ) {
        ModelFitVerdict.BLOCK -> DownloadFit.DoesNotFit
        ModelFitVerdict.WARN -> DownloadFit.FitsWithLowSpaceWarning
        ModelFitVerdict.OK -> DownloadFit.Fits
    }

    /**
     * Whether chat can actually answer with [config] right now.
     *
     * For cloud providers this reuses the connection tester's own gap rules
     * ([ConnectionTestPlanner.configurationGap]) so the banner never disagrees
     * with Settings about whether the active provider is configured. On-device
     * providers carry no config-level gap, so readiness comes from the device:
     * the AI Core status and the LiteRT model table, mirroring
     * [com.opendroid.ai.core.llm.providers.HybridOnDeviceProvider]'s
     * primary-then-fallback behaviour across the two backends.
     */
    fun canAnswer(
        config: LLMConfig,
        liteRtModels: List<ModelEntity>,
        aiCoreAvailable: Boolean
    ): Boolean {
        val active = ProviderCatalog.canonicalName(config.activeProvider)
        if (ProviderCatalog.isOnDevice(active)) {
            val selectedId = config.selectedModelFor(active)
            return when (OnDeviceModelRegistry.findById(selectedId)?.backend) {
                // Hybrid falls back to LiteRT when AI Core is unavailable,
                // so a ready LiteRT model can answer here too — the AI_CORE
                // branch must mirror that instead of reporting "can't answer"
                // on devices where chat would in fact work.
                OnDeviceBackend.AI_CORE -> aiCoreAvailable ||
                    liteRtModels.any { it.status == ModelStatus.READY }
                OnDeviceBackend.LITERT_LM -> aiCoreAvailable ||
                    liteRtModels.any { it.id == selectedId && it.status == ModelStatus.READY }
                // Custom imports and legacy ids: usable when either backend is.
                null -> aiCoreAvailable || liteRtModels.any { it.status == ModelStatus.READY }
            }
        }
        return ConnectionTestPlanner.configurationGap(config, active) == null
    }
}

/**
 * Live "can chat answer?" state, fused from the same stores Settings writes:
 * the LLM config, the on-device model table, and the AI Core feature status.
 * Emits again whenever any of them changes, so a completed setup (from
 * onboarding, Settings, or a finished model download) clears the limited
 * state without an app restart.
 */
@Singleton
class ProviderSetupReadiness @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val gemmaProvider: GemmaProvider
) {
    // AI Core status is a device capability probe; it is checked once per
    // collection of [canAnswer] rather than polled.
    private val aiCoreAvailable: Flow<Boolean> = flow {
        emit(runCatching { gemmaProvider.isAvailable() }.getOrDefault(false))
    }

    val canAnswer: Flow<Boolean> = combine(
        settingsRepository.llmConfig,
        modelRepository.allModelsFlow,
        aiCoreAvailable
    ) { config, models, aiCore ->
        ProviderSetupPolicy.canAnswer(config, models, aiCore)
    }
}
