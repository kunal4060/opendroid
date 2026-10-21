package com.opendroid.ai.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HmacSHA256 hashing for the app-lock PIN (A5).
 *
 * The PIN itself is never stored anywhere. What persists is a self-describing
 * record — `v1$<iterations>$<base64 salt>$<base64 derived key>` — inside the
 * Keystore-envelope store ([AppLockStore]), so the at-rest value is both salted
 * and encrypted. Verification derives the candidate with the record's own
 * parameters and compares in constant time.
 *
 * Pure JVM (no Android types) so the format and the verification logic are
 * unit-testable off-device.
 */
object PinHasher {

    /** OWASP's current PBKDF2-HmacSHA256 work factor for password storage. */
    const val ITERATIONS = 600_000

    /**
     * Highest record-supplied iteration count [verify] will honour. The
     * record is data (it round-trips through storage), and an unbounded
     * value would send PBKDF2 into an effectively endless derivation on
     * the unlock path. Mirrors BackupCrypto's cap on header-supplied
     * counts; well above [ITERATIONS] and any plausible future work
     * factor this app writes.
     */
    const val MAX_ITERATIONS = 2_000_000

    private const val RECORD_VERSION = "v1"
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 6

    /** A lock PIN is 4–6 digits, nothing else. */
    fun isValidPinFormat(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

    /** Creates a fresh salted record for [pin]. Callers must validate format first. */
    fun createRecord(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(pin, salt, ITERATIONS)
        val encoder = Base64.getEncoder()
        return listOf(
            RECORD_VERSION,
            ITERATIONS.toString(),
            encoder.encodeToString(salt),
            encoder.encodeToString(derived)
        ).joinToString("$")
    }

    /**
     * Constant-time verification against a record from [createRecord]. Any
     * malformed record (unknown version, bad fields, undecodable parts) is a
     * plain `false` — never an exception the caller could mistake for a match.
     */
    fun verify(pin: String, record: String): Boolean {
        val parts = record.split('$')
        if (parts.size != 4 || parts[0] != RECORD_VERSION) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations <= 0 || iterations > MAX_ITERATIONS) return false
        val decoder = Base64.getDecoder()
        val salt = try {
            decoder.decode(parts[2])
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = try {
            decoder.decode(parts[3])
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (salt.isEmpty() || expected.isEmpty()) return false
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
