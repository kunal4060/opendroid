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

    /** Free space that must remain after a first-run model download. */
    const val MIN_FREE_AFTER_DOWNLOAD_BYTES: Long = 1_000_000_000L

    /**
     * A9-style fit gate: compares the model's real [OnDeviceModelSpec.expectedSize]
     * (the same bytes the download worker enforces) against the device's actual
     * free space, instead of steering a new user into a download that cannot land.
     */
    fun assessDownloadFit(modelBytes: Long, freeBytes: Long): DownloadFit = when {
        modelBytes > freeBytes -> DownloadFit.DoesNotFit
        freeBytes - modelBytes < MIN_FREE_AFTER_DOWNLOAD_BYTES ->
            DownloadFit.FitsWithLowSpaceWarning
        else -> DownloadFit.Fits
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
                OnDeviceBackend.AI_CORE -> aiCoreAvailable
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
