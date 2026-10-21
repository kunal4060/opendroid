package com.opendroid.ai.core.external

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The external-automation API token (feature C7).
 *
 * The token is a 192-bit random secret generated on-device when the user
 * turns the feature on in Settings → External automation. It is shown to the
 * user exactly once per generation so they can paste it into Tasker/ADB
 * scripts; afterwards only a display hint (last 4 characters) is kept for
 * the Settings screen.
 *
 * The token itself is NEVER stored. What persists (in the app-private
 * [ExternalAutomationStore] preference file) is a self-describing salted-hash
 * record — `v1$<base64 salt>$<base64 SHA-256(salt ‖ token)>` — so a leaked
 * preferences file yields nothing replayable. Because the token carries 192
 * bits of entropy, a salted fast hash is sufficient here — this is not a
 * human-chosen password, so PBKDF2-style stretching (see `PinHasher` for the
 * app PIN) buys nothing against guessing a random 192-bit value.
 *
 * Pure JVM (no Android types) so generation, record format, and verification
 * are unit-testable off-device.
 */
object ExternalApiToken {

    private const val RECORD_VERSION = "v1"
    private const val TOKEN_BYTES = 24
    private const val SALT_BYTES = 16

    /** Minimum/maximum accepted token length; real tokens are always 32 chars. */
    const val MIN_TOKEN_LENGTH = 16
    const val MAX_TOKEN_LENGTH = 128

    /** Generates a fresh token: 24 random bytes, Base64-URL without padding (32 chars). */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Creates the salted-hash record persisted in place of the token. */
    fun createRecord(token: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val encoder = Base64.getEncoder()
        return listOf(
            RECORD_VERSION,
            encoder.encodeToString(salt),
            encoder.encodeToString(hash(salt, token))
        ).joinToString("$")
    }

    /**
     * Constant-time verification against a record from [createRecord]. Any
     * malformed record (unknown version, bad fields, undecodable parts) is a
     * plain `false` — never an exception a caller could mistake for a match.
     */
    fun verify(token: String, record: String): Boolean {
        if (!isPlausibleFormat(token)) return false
        val parts = record.split('$')
        if (parts.size != 3 || parts[0] != RECORD_VERSION) return false
        val decoder = Base64.getDecoder()
        val salt = try {
            decoder.decode(parts[1])
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = try {
            decoder.decode(parts[2])
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (salt.isEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(expected, hash(salt, token))
    }

    /**
     * Cheap shape check used for an early reject (and by [verify]): tokens
     * are Base64-URL characters within a sane length band. This rejects
     * garbage before any hashing happens, without revealing anything about
     * the real token.
     */
    fun isPlausibleFormat(token: String): Boolean =
        token.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH &&
            token.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Display-only hint kept for Settings ("••••wxyz"); never enough to authenticate. */
    fun hintFor(token: String): String = token.takeLast(4)

    private fun hash(salt: ByteArray, token: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(token.toByteArray(Charsets.UTF_8))
    }
}
