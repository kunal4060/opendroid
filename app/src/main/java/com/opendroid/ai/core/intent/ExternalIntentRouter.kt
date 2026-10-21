package com.opendroid.ai.core.intent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single hand-off point between MainActivity (which receives every
 * external intent) and the Compose UI (which acts on it).
 *
 * The flow replays the latest launch (replay = 1) so a launch that arrives
 * while the UI is not yet composed — cold start behind splash/onboarding, or
 * any start while the A5 lock gate is showing — is still delivered when the
 * consumer appears, instead of being dropped. Replay alone would re-deliver
 * on every new collector, so consumption is claim-based: [claim] succeeds
 * exactly once per published launch instance, and a consumer that disappears
 * mid-handling can [releaseClaim] to hand the launch back.
 */
@Singleton
class ExternalIntentRouter @Inject constructor() {

    private val _launches = MutableSharedFlow<ExternalLaunch>(
        replay = 1,
        extraBufferCapacity = 4
    )
    val launches: SharedFlow<ExternalLaunch> = _launches.asSharedFlow()

    @Volatile
    private var claimedLaunch: ExternalLaunch? = null

    /** Publishes [launch] as the latest external launch, replacing any unconsumed one. */
    fun publish(launch: ExternalLaunch) {
        _launches.tryEmit(launch)
    }

    /** The latest published launch, whether or not it has been claimed yet. */
    fun peek(): ExternalLaunch? = _launches.replayCache.lastOrNull()

    /**
     * Atomically marks [launch] as being handled. Returns false when this
     * exact instance was already claimed — identity, not equality, so a second
     * share with identical content still counts as a new launch.
     */
    fun claim(launch: ExternalLaunch): Boolean = synchronized(this) {
        if (claimedLaunch === launch) {
            false
        } else {
            claimedLaunch = launch
            true
        }
    }

    /** Undoes [claim] for [launch] (e.g. handling was deferred), making it claimable again. */
    fun releaseClaim(launch: ExternalLaunch) {
        synchronized(this) {
            if (claimedLaunch === launch) claimedLaunch = null
        }
    }
}
