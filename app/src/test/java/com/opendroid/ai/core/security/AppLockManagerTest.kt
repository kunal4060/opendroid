package com.opendroid.ai.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [AppLockStore] so the manager's state machine runs off-device. */
private class FakeAppLockStore : AppLockStore {
    private var enabled = false
    private var timeout = AndroidAppLockStore.DEFAULT_TIMEOUT_SEC
    private var pin: String? = null
    private var attempts = 0
    private var cooldownUntil = 0L

    override fun isEnabled(): Boolean = enabled
    override fun setEnabled(enabled: Boolean): Boolean {
        this.enabled = enabled
        return true
    }

    override fun timeoutSec(): Int = timeout
    override fun setTimeoutSec(seconds: Int): Boolean {
        timeout = seconds.coerceAtLeast(0)
        return true
    }

    override fun pinRecord(): String? = pin
    override fun setPinRecord(record: String?): Boolean {
        pin = record
        return true
    }

    override fun failedAttempts(): Int = attempts
    override fun cooldownUntilMs(): Long = cooldownUntil
    override fun setAttemptState(failedAttempts: Int, cooldownUntilMs: Long): Boolean {
        attempts = failedAttempts
        cooldownUntil = cooldownUntilMs
        return true
    }

    override fun clearAll(): Boolean {
        enabled = false
        pin = null
        attempts = 0
        cooldownUntil = 0L
        return true
    }
}

class AppLockManagerTest {

    private var now = 1_000_000L

    private fun manager(store: FakeAppLockStore): AppLockManager =
        AppLockManager(store) { now }

    @Test
    fun `cold start is locked when the lock is enabled`() {
        val store = FakeAppLockStore()
        store.setEnabled(true)
        assertTrue(manager(store).isLocked.value)
    }

    @Test
    fun `cold start is unlocked when the lock is disabled`() {
        assertFalse(manager(FakeAppLockStore()).isLocked.value)
    }

    @Test
    fun `return before the timeout stays unlocked`() {
        val store = FakeAppLockStore()
        store.setEnabled(true)
        store.setTimeoutSec(60)
        val manager = manager(store)
        manager.unlock()

        manager.onAppBackgrounded()
        now += 30_000
        manager.onAppForegrounded()

        assertFalse(manager.isLocked.value)
    }

    @Test
    fun `return after the timeout re-locks`() {
        val store = FakeAppLockStore()
        store.setEnabled(true)
        store.setTimeoutSec(60)
        val manager = manager(store)
        manager.unlock()

        manager.onAppBackgrounded()
        now += 61_000
        manager.onAppForegrounded()

        assertTrue(manager.isLocked.value)
    }

    @Test
    fun `immediate timeout re-locks on any return`() {
        val store = FakeAppLockStore()
        store.setEnabled(true)
        store.setTimeoutSec(0)
        val manager = manager(store)
        manager.unlock()

        manager.onAppBackgrounded()
        now += 1_000
        manager.onAppForegrounded()

        assertTrue(manager.isLocked.value)
    }

    @Test
    fun `backgrounding never locks a disabled lock`() {
        val manager = manager(FakeAppLockStore())
        manager.onAppBackgrounded()
        now += 3_600_000
        manager.onAppForegrounded()
        assertFalse(manager.isLocked.value)
    }

    @Test
    fun `disabling the lock clears the locked state`() {
        val store = FakeAppLockStore()
        store.setEnabled(true)
        val manager = manager(store)
        assertTrue(manager.isLocked.value)

        manager.setEnabled(false)

        assertFalse(manager.lockEnabled.value)
        assertFalse(manager.isLocked.value)
    }

    @Test
    fun `setPin rejects malformed pins`() {
        val manager = manager(FakeAppLockStore())
        assertFalse(manager.setPin("123"))
        assertFalse(manager.setPin("1234567"))
        assertFalse(manager.setPin("12a4"))
        assertFalse(manager.hasPin.value)
        assertTrue(manager.setPin("1234"))
        assertTrue(manager.hasPin.value)
    }

    @Test
    fun `verifyPin without a pin reports NoPinSet`() {
        val manager = manager(FakeAppLockStore())
        assertEquals(
            AppLockManager.PinVerification.NoPinSet,
            manager.verifyPin("1234")
        )
    }

    @Test
    fun `correct pin succeeds and resets the attempt counter`() {
        val manager = manager(FakeAppLockStore())
        manager.setPin("4321")

        assertEquals(
            AppLockManager.PinVerification.Incorrect(4),
            manager.verifyPin("0000")
        )
        assertEquals(
            AppLockManager.PinVerification.Incorrect(3),
            manager.verifyPin("0000")
        )
        assertEquals(
            AppLockManager.PinVerification.Success,
            manager.verifyPin("4321")
        )
        // Counter was reset by the success: the next failure starts from 4 again.
        assertEquals(
            AppLockManager.PinVerification.Incorrect(4),
            manager.verifyPin("0000")
        )
    }

    @Test
    fun `five failures start a cooldown that even the correct pin cannot bypass`() {
        val manager = manager(FakeAppLockStore())
        manager.setPin("4321")

        repeat(AppLockManager.MAX_FAILED_ATTEMPTS - 1) { manager.verifyPin("0000") }
        val fifth = manager.verifyPin("0000")
        assertTrue(fifth is AppLockManager.PinVerification.InCooldown)
        assertEquals(
            AppLockManager.BASE_COOLDOWN_MS,
            (fifth as AppLockManager.PinVerification.InCooldown).remainingMs
        )

        // Even the right PIN is refused while cooling down.
        assertTrue(manager.verifyPin("4321") is AppLockManager.PinVerification.InCooldown)

        // After the cooldown, the correct PIN succeeds again.
        now += AppLockManager.BASE_COOLDOWN_MS + 1
        assertEquals(
            AppLockManager.PinVerification.Success,
            manager.verifyPin("4321")
        )
    }

    @Test
    fun `cooldown escalates after it expires and another failure happens`() {
        val manager = manager(FakeAppLockStore())
        manager.setPin("4321")

        repeat(AppLockManager.MAX_FAILED_ATTEMPTS) { manager.verifyPin("0000") }
        now += AppLockManager.BASE_COOLDOWN_MS + 1

        val next = manager.verifyPin("0000")
        assertTrue(next is AppLockManager.PinVerification.InCooldown)
        assertEquals(
            AppLockManager.BASE_COOLDOWN_MS * 2,
            (next as AppLockManager.PinVerification.InCooldown).remainingMs
        )
    }

    @Test
    fun `successful unlock clears a persisted cooldown`() {
        val store = FakeAppLockStore()
        store.setEnabled(true)
        val manager = manager(store)
        manager.setPin("4321")
        repeat(AppLockManager.MAX_FAILED_ATTEMPTS) { manager.verifyPin("0000") }
        assertTrue(manager.pinCooldownRemainingMs() > 0)

        // A biometric success path calls unlock(); the PIN cooldown must not
        // outlive a proven identity.
        manager.unlock()
        assertEquals(0L, manager.pinCooldownRemainingMs())
        assertFalse(manager.isLocked.value)
    }
}
