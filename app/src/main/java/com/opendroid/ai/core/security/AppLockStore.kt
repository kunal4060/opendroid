package com.opendroid.ai.core.security

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Persistence for the app lock (A5): the enabled flag, the lock timeout, the
 * PIN hash record, and the failed-attempt/cooldown state.
 *
 * Everything lives in the same Keystore-envelope layer as the other secret
 * stores ([KeystoreSecretRecords] over an app-private preference file), so no
 * lock state — and above all no PIN material — ever sits in plaintext
 * preferences. The PIN itself is never stored at all; only the salted PBKDF2
 * record produced by [PinHasher] is.
 *
 * The lock is a UI gate: the Keystore key here is deliberately NOT
 * user-authentication-bound, exactly like the other stores, so background
 * work keeps decrypting while the UI is locked.
 */
interface AppLockStore {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean): Boolean

    /** Seconds the app may stay backgrounded before it re-locks. 0 = immediately. */
    fun timeoutSec(): Int
    fun setTimeoutSec(seconds: Int): Boolean

    /** The [PinHasher] record, or null when no app PIN has been set. */
    fun pinRecord(): String?
    fun setPinRecord(record: String?): Boolean

    fun failedAttempts(): Int

    /** Epoch millis until which PIN entry is in cooldown; 0 when not cooling down. */
    fun cooldownUntilMs(): Long
    fun setAttemptState(failedAttempts: Int, cooldownUntilMs: Long): Boolean

    /**
     * Monotonic (`SystemClock.elapsedRealtime`) millis until which PIN
     * entry is in cooldown; 0 when the store tracks no monotonic deadline.
     * The wall-clock deadline alone is not a rate limit — advancing the
     * device clock used to expire the cooldown instantly (audit-4 #8) —
     * so [AppLockManager] takes the later of the two.
     */
    fun cooldownUntilElapsedRealtimeMs(): Long = 0L

    /**
     * Records the attempt state with both cooldown deadlines. The default
     * keeps stores that track only the wall-clock deadline working; the
     * Android store overrides it to persist the monotonic deadline too.
     */
    fun setAttemptState(
        failedAttempts: Int,
        cooldownUntilMs: Long,
        cooldownUntilElapsedRealtimeMs: Long
    ): Boolean = setAttemptState(failedAttempts, cooldownUntilMs)

    /** Removes every lock record (PIN, flags, attempt state). Used by the wipe. */
    fun clearAll(): Boolean
}

class AndroidAppLockStore(
    context: Context,
    preferenceName: String = PREFERENCES_NAME,
    keyAlias: String = KEY_ALIAS
) : AppLockStore {

    private val records = KeystoreSecretRecords(
        storage = SharedPreferencesSecretRecordStorage(
            context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        ),
        cipher = AndroidKeyStoreAeadCipher(keyAlias)
    )

    private val lock = Any()

    override fun isEnabled(): Boolean = read(KEY_ENABLED) == "true"

    override fun setEnabled(enabled: Boolean): Boolean = write(KEY_ENABLED, enabled.toString())

    override fun timeoutSec(): Int = read(KEY_TIMEOUT_SEC)?.toIntOrNull() ?: DEFAULT_TIMEOUT_SEC

    override fun setTimeoutSec(seconds: Int): Boolean =
        write(KEY_TIMEOUT_SEC, seconds.coerceAtLeast(0).toString())

    override fun pinRecord(): String? = read(KEY_PIN_RECORD)

    override fun setPinRecord(record: String?): Boolean =
        if (record == null) remove(KEY_PIN_RECORD) else write(KEY_PIN_RECORD, record)

    override fun failedAttempts(): Int =
        read(KEY_ATTEMPT_STATE)?.substringBefore(':')?.toIntOrNull() ?: 0

    // The attempt-state record is "attempts:wallDeadline[:elapsedDeadline]";
    // records written before the monotonic deadline existed have two fields.
    override fun cooldownUntilMs(): Long =
        read(KEY_ATTEMPT_STATE)?.split(':')?.getOrNull(1)?.toLongOrNull() ?: 0L

    override fun cooldownUntilElapsedRealtimeMs(): Long =
        read(KEY_ATTEMPT_STATE)?.split(':')?.getOrNull(2)?.toLongOrNull() ?: 0L

    override fun setAttemptState(failedAttempts: Int, cooldownUntilMs: Long): Boolean =
        setAttemptState(failedAttempts, cooldownUntilMs, 0L)

    override fun setAttemptState(
        failedAttempts: Int,
        cooldownUntilMs: Long,
        cooldownUntilElapsedRealtimeMs: Long
    ): Boolean =
        write(KEY_ATTEMPT_STATE, "$failedAttempts:$cooldownUntilMs:$cooldownUntilElapsedRealtimeMs")

    override fun clearAll(): Boolean = synchronized(lock) {
        var allSuccess = true
        for (key in listOf(KEY_ENABLED, KEY_TIMEOUT_SEC, KEY_PIN_RECORD, KEY_ATTEMPT_STATE)) {
            if (!remove(key)) allSuccess = false
        }
        allSuccess
    }

    private fun read(key: String): String? = synchronized(lock) {
        when (val result = records.read(key, aad(key))) {
            is SecretRecordResult.Success ->
                result.value?.let { String(it, StandardCharsets.UTF_8) }
            // An unreadable record reads as "absent". For the enabled flag that
            // fails open (lock off) — acceptable because the envelope being
            // unrecoverable means the store's key material is gone, and the
            // recovery path is the same re-entry story as the other stores.
            else -> null
        }
    }

    private fun write(key: String, value: String): Boolean = synchronized(lock) {
        when (records.write(key, aad(key), value.toByteArray(StandardCharsets.UTF_8))) {
            is SecretRecordResult.Success -> true
            else -> false
        }
    }

    private fun remove(key: String): Boolean = synchronized(lock) {
        when (records.removeRecord(key)) {
            is SecretRecordResult.Success -> true
            else -> false
        }
    }

    private fun aad(key: String): String = "$AAD_PREFIX:$key"

    companion object {
        const val PREFERENCES_NAME = "opendroid_app_lock"
        const val KEY_ALIAS = "opendroid.app_lock.aes_gcm.v1"

        /** Research default: re-lock after 1 minute in the background. */
        const val DEFAULT_TIMEOUT_SEC = 60

        private const val AAD_PREFIX = "app-lock"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TIMEOUT_SEC = "timeout_sec"
        private const val KEY_PIN_RECORD = "pin_record"
        private const val KEY_ATTEMPT_STATE = "attempt_state"
    }
}
