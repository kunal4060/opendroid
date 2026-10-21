package com.opendroid.ai.core.llm

/**
 * The chat routing state surfaced by the A8 offline-first badge.
 *
 * Every variant is derived from real signals by [RoutingStatusResolver] —
 * configured provider, live connectivity, on-device readiness and the
 * provider that actually served the last answer — so the badge cannot be a
 * static icon: it flips when connectivity drops, when routing switches, and
 * after a failover answer.
 */
sealed interface RoutingStatus {
    /** Online, answers come from the named cloud provider. */
    data class Cloud(val provider: String) : RoutingStatus

    /** Answers come from an on-device backend (AI Core / LiteRT-LM). */
    data class OnDevice(val backend: String) : RoutingStatus

    /**
     * Offline with a cloud provider configured, but an on-device route is
     * available and will answer (see the last-resort rule in
     * [LLMProviderFactory.getPlanningProviders]).
     */
    data class OfflineWillUseOnDevice(val backend: String) : RoutingStatus

    /**
     * Offline and no on-device route is ready: there is nothing that can
     * answer. The chat send path consults this same state and refuses to
     * dispatch a request that would only hang or fail opaquely.
     */
    data object OfflineNoRoute : RoutingStatus
}

/**
 * Pure fusion of the four routing signals into a [RoutingStatus]. Kept free
 * of Android/framework dependencies so the full decision table is unit
 * testable on the JVM.
 */
object RoutingStatusResolver {

    fun resolve(
        activeProvider: String,
        fallbackProviders: List<String>,
        isOnline: Boolean,
        onDeviceAvailable: Boolean,
        onDeviceBackendName: String?,
        lastAnsweredBy: String?
    ): RoutingStatus {
        val active = ProviderCatalog.canonicalName(activeProvider)
        val fallbacks = fallbackProviders.map { ProviderCatalog.canonicalName(it) }
        val backend = onDeviceBackendName
            ?.takeIf { it.isNotBlank() && it != "None available" }
            ?: "Local model"

        // A last-served record only counts while it still describes this
        // configuration: the active provider itself, a configured fallback,
        // or an on-device route (which the offline last-resort rule can add
        // without it ever appearing in the configured fallback list). A stale
        // record from a since-removed provider must not haunt the badge.
        val lastServed = lastAnsweredBy
            ?.let { ProviderCatalog.canonicalName(it) }
            ?.takeIf { it == active || it in fallbacks || ProviderCatalog.isOnDevice(it) }

        if (!isOnline) {
            return when {
                // The configured route itself is on-device: reachable offline
                // as long as a backend is actually ready.
                ProviderCatalog.isOnDevice(active) ->
                    if (onDeviceAvailable) {
                        RoutingStatus.OnDevice(backend)
                    } else {
                        RoutingStatus.OfflineNoRoute
                    }
                // Cloud configured, but the last-resort on-device route exists.
                onDeviceAvailable -> RoutingStatus.OfflineWillUseOnDevice(backend)
                else -> RoutingStatus.OfflineNoRoute
            }
        }

        // Online: an answer that actually failed over outranks the configured
        // route — the badge reports who answered, until the next answer (from
        // the configured provider) replaces the record.
        if (lastServed != null && lastServed != active) {
            return if (ProviderCatalog.isOnDevice(lastServed)) {
                RoutingStatus.OnDevice(backend)
            } else {
                RoutingStatus.Cloud(lastServed)
            }
        }

        return if (ProviderCatalog.isOnDevice(active)) {
            RoutingStatus.OnDevice(backend)
        } else {
            RoutingStatus.Cloud(active)
        }
    }
}
