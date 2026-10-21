package com.opendroid.ai.core.llm

import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Single source of truth for chat routing state (A8).
 *
 * Fuses four real signals — the configured provider/fallbacks from
 * [SettingsRepository.llmConfig], live connectivity from
 * [DeviceStateProvider.isOnline], on-device readiness sampled from
 * [HybridOnDeviceProvider][com.opendroid.ai.core.llm.providers.HybridOnDeviceProvider],
 * and the last-served provider from [LastServedProviderTracker] — through
 * [RoutingStatusResolver]. The Chat header badge renders [status] and the
 * chat send path consults the very same [status], so the indicator and the
 * send behaviour can never disagree.
 */
@Singleton
class RoutingStatusRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val deviceStateProvider: DeviceStateProvider,
    private val hybridOnDeviceProvider: Provider<com.opendroid.ai.core.llm.providers.HybridOnDeviceProvider>,
    private val lastServedProviderTracker: LastServedProviderTracker
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class OnDeviceSnapshot(val available: Boolean, val backendName: String?)

    // On-device readiness has no change Flow of its own (a model download
    // finishing in Model Manager is what flips it), so it is sampled: on
    // every config/connectivity change and on a slow poll while the process
    // lives.
    private val onDeviceSnapshot = MutableStateFlow(OnDeviceSnapshot(available = false, backendName = null))

    init {
        scope.launch {
            combine(settingsRepository.llmConfig, deviceStateProvider.isOnline) { _, _ -> Unit }
                .collect { refreshOnDeviceSnapshot() }
        }
        scope.launch {
            while (isActive) {
                delay(ON_DEVICE_POLL_MILLIS)
                refreshOnDeviceSnapshot()
            }
        }
    }

    private suspend fun refreshOnDeviceSnapshot() {
        val hybrid = hybridOnDeviceProvider.get()
        val available = runCatching { hybrid.isAvailable() }.getOrDefault(false)
        val backendName = if (available) {
            runCatching { hybrid.getActiveBackendName() }.getOrNull()
        } else {
            null
        }
        onDeviceSnapshot.value = OnDeviceSnapshot(available, backendName)
    }

    val status: StateFlow<RoutingStatus> = combine(
        settingsRepository.llmConfig,
        smoothedConnectivity(),
        onDeviceSnapshot,
        lastServedProviderTracker.lastAnsweredBy
    ) { config, online, onDevice, lastServed ->
        RoutingStatusResolver.resolve(
            activeProvider = config.activeProvider,
            fallbackProviders = config.fallbackProviders,
            isOnline = online,
            onDeviceAvailable = onDevice.available,
            onDeviceBackendName = onDevice.backendName,
            lastAnsweredBy = lastServed
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = RoutingStatus.Cloud(
            ProviderCatalog.canonicalName(LLMConfig().activeProvider)
        )
    )

    /**
     * Connectivity for display: losing the network is reflected immediately
     * (that is the transition the badge exists for), while a regain must
     * hold for ~1 s before it is believed — dual-SIM/VPN handovers otherwise
     * flap the badge. The send guard reads the same smoothed [status], so
     * badge and guard always agree.
     */
    private fun smoothedConnectivity(): Flow<Boolean> = channelFlow {
        var lastEmitted: Boolean? = null
        deviceStateProvider.isOnline.collect { online ->
            if (online == lastEmitted) return@collect
            if (!online) {
                lastEmitted = false
                send(false)
            } else {
                delay(RECONNECT_CONFIRM_MILLIS)
                // Re-read the live value: if it dropped again during the
                // confirmation window, stay offline.
                if (deviceStateProvider.isOnline.value) {
                    lastEmitted = true
                    send(true)
                }
            }
        }
    }

    private companion object {
        const val RECONNECT_CONFIRM_MILLIS = 1_000L
        const val ON_DEVICE_POLL_MILLIS = 30_000L
    }
}
