package com.opendroid.ai.core.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app-lock gate (A5).
 *
 * This is a UI/lifecycle gate only — it never re-encrypts app data and never
 * touches the other stores' Keystore keys, so background work (provider calls,
 * auto-reply, scheduled tasks) keeps running while the UI is locked.
 *
 * Lock state is derived, not flagged: a cold process start is locked whenever
 * the lock is enabled, and a return from the background re-locks once the
 * configured timeout has elapsed (tracked through [ProcessLifecycleOwner]).
 * The NavHost is simply never composed while [isLocked] is true, so no chat,
 * settings, or memory content exists underneath the gate to leak into a
 * screenshot or the recents thumbnail (MainActivity also sets FLAG_SECURE
 * while locked, as a second layer).
 */
class AppLockManager(
    private val store: AppLockStore,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val _lockEnabled = MutableStateFlow(store.isEnabled())
    val lockEnabled: StateFlow<Boolean> = _lockEnabled.asStateFlow()

    private val _timeoutSec = MutableStateFlow(store.timeoutSec())
    val timeoutSec: StateFlow<Int> = _timeoutSec.asStateFlow()

    private val _hasPin = MutableStateFlow(store.pinRecord() != null)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    /** Cold start = locked when enabled; there is no persisted "unlocked" flag to go stale. */
    private val _isLocked = MutableStateFlow(store.isEnabled())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    @Volatile
    private var backgroundedAtMs: Long? = null

    private val started = AtomicBoolean(false)

    /**
     * Registers the process-lifecycle observer. Called once from
     * [com.opendroid.ai.OpenDroidApp.onCreate] (main thread, as
     * [ProcessLifecycleOwner] requires); further calls are no-ops.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onAppForegrounded()
            override fun onStop(owner: LifecycleOwner) = onAppBackgrounded()
        })
    }

    internal fun onAppBackgrounded() {
        if (_lockEnabled.value) {
            backgroundedAtMs = nowMs()
        }
    }

    internal fun onAppForegrounded() {
        val backgroundedAt = backgroundedAtMs ?: return
        backgroundedAtMs = null
        if (!_lockEnabled.value) {
            _isLocked.value = false
            return
        }
        val timeoutMs = _timeoutSec.value * 1000L
        if (timeoutMs <= 0L || nowMs() - backgroundedAt >= timeoutMs) {
            _isLocked.value = true
        }
    }

    /**
     * Enables or disables the lock. Callers (Settings) must have authenticated
     * the user first — enabling requires proof of identity, and disabling
     * requires a successful authentication, per the checklist.
     */
    fun setEnabled(enabled: Boolean) {
        if (store.setEnabled(enabled)) {
            _lockEnabled.value = enabled
            if (!enabled) {
                _isLocked.value = false
                backgroundedAtMs = null
            }
        }
    }

    fun setTimeoutSec(seconds: Int) {
        if (store.setTimeoutSec(seconds)) {
            _timeoutSec.value = seconds.coerceAtLeast(0)
        }
    }

    /** Sets (or replaces) the app PIN. Returns false when the format is not 4–6 digits. */
    fun setPin(pin: String): Boolean {
        if (!PinHasher.isValidPinFormat(pin)) return false
        if (!store.setPinRecord(PinHasher.createRecord(pin))) return false
        store.setAttemptState(0, 0L, 0L)
        _hasPin.value = true
        return true
    }

    fun removePin() {
        if (store.setPinRecord(null)) {
            store.setAttemptState(0, 0L, 0L)
            _hasPin.value = false
        }
    }

    /** Outcome of a PIN entry attempt; see [verifyPin]. */
    sealed interface PinVerification {
        data object Success : PinVerification
        data object NoPinSet : PinVerification

        /** Wrong PIN; [attemptsRemaining] tries left before the cooldown starts. */
        data class Incorrect(val attemptsRemaining: Int) : PinVerification

        /** Too many wrong PINs; entry is blocked for [remainingMs]. */
        data class InCooldown(val remainingMs: Long) : PinVerification
    }

    /**
     * Verifies a PIN entry against the stored record, applying the attempt
     * limit: [MAX_FAILED_ATTEMPTS] wrong entries start an escalating cooldown
     * (30 s, doubling per further failure, capped at 5 min). Attempt state is
     * persisted in the store, so killing the process does not reset it.
     * A success clears the attempt state.
     */
    fun verifyPin(pin: String): PinVerification {
        val record = store.pinRecord() ?: return PinVerification.NoPinSet
        val now = nowMs()
        val remaining = cooldownRemainingMs(now)
        if (remaining > 0) {
            return PinVerification.InCooldown(remaining)
        }
        if (PinHasher.verify(pin, record)) {
            store.setAttemptState(0, 0L, 0L)
            return PinVerification.Success
        }
        val attempts = store.failedAttempts() + 1
        if (attempts < MAX_FAILED_ATTEMPTS) {
            store.setAttemptState(attempts, 0L, 0L)
            return PinVerification.Incorrect(MAX_FAILED_ATTEMPTS - attempts)
        }
        val exponent = minOf(attempts - MAX_FAILED_ATTEMPTS, MAX_COOLDOWN_EXPONENT)
        val cooldownMs = minOf(BASE_COOLDOWN_MS * (1L shl exponent), MAX_COOLDOWN_MS)
        store.setAttemptState(attempts, now + cooldownMs, elapsedRealtimeMs() + cooldownMs)
        return PinVerification.InCooldown(cooldownMs)
    }

    /**
     * Milliseconds left in the PIN cooldown. Two deadlines are persisted
     * (audit-4 #8): a wall-clock one and a monotonic elapsedRealtime one.
     * The wall clock alone is not a rate limit — advancing the device
     * clock used to expire the cooldown instantly — so whichever deadline
     * expires later wins. The result is capped at [MAX_COOLDOWN_MS]: no
     * legitimate cooldown is longer, and a larger remainder can only be
     * an artifact of the monotonic clock having reset under an old
     * deadline (reboot) or of the wall clock having moved backwards.
     */
    private fun cooldownRemainingMs(now: Long = nowMs()): Long {
        val wallRemaining = store.cooldownUntilMs() - now
        val elapsedRemaining = store.cooldownUntilElapsedRealtimeMs() - elapsedRealtimeMs()
        return maxOf(wallRemaining, elapsedRemaining).coerceIn(0L, MAX_COOLDOWN_MS)
    }

    /** Milliseconds left in the current PIN cooldown; 0 when entry is allowed. */
    fun pinCooldownRemainingMs(): Long = cooldownRemainingMs()

    /** Marks the UI unlocked after any successful authentication (biometric or PIN). */
    fun unlock() {
        store.setAttemptState(0, 0L, 0L)
        backgroundedAtMs = null
        _isLocked.value = false
    }

    /** Re-locks immediately (when enabled). */
    fun lockNow() {
        if (_lockEnabled.value) {
            _isLocked.value = true
        }
    }

    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        const val BASE_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 300_000L
        private const val MAX_COOLDOWN_EXPONENT = 4
    }
}
