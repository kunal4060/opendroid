package com.opendroid.ai.core.launch

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The hand-off point between [com.opendroid.ai.MainActivity] (which receives
 * every external intent) and the Compose navigation layer (which is the only
 * thing that knows how to honour one).
 *
 * A launch is *pending* until the dashboard consumes it. Keeping it in a
 * [StateFlow] — rather than firing an event into a flow nobody may be
 * collecting yet — is what lets launches queue behind the things that must
 * resolve first: the splash routing, onboarding, and the A5 app-lock gate
 * (the NavHost is not even composed while the app is locked, so an intent
 * that arrives locked is delivered the moment the user unlocks, never
 * leaked past the gate and never lost).
 *
 * Each submission carries a monotonically increasing sequence number so two
 * identical launches in a row (widget mic tapped twice) are still two
 * distinct pendings, and so a consumer can only clear the exact submission
 * it handled.
 */
@Singleton
class ExternalIntentRouter @Inject constructor() {

    /** A submitted launch awaiting consumption, tagged with its sequence. */
    data class Pending(val launch: ExternalLaunch, val sequence: Long)

    private val sequenceCounter = AtomicLong(0L)

    private val _pending = MutableStateFlow<Pending?>(null)

    /** The launch waiting to be honoured, or null when there is none. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** Records a launch received by the activity. Latest one wins. */
    fun submit(launch: ExternalLaunch) {
        _pending.value = Pending(launch, sequenceCounter.incrementAndGet())
    }

    /** Clears the pending launch, but only if it is still the one handled. */
    fun consume(sequence: Long) {
        val current = _pending.value
        if (current != null && current.sequence == sequence) {
            _pending.value = null
        }
    }
}

/** Lets composables reach the router without a ViewModel of their own. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExternalIntentRouterEntryPoint {
    fun externalIntentRouter(): ExternalIntentRouter
}
