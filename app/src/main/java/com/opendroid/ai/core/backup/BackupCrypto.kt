package com.opendroid.ai.core.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Plaintext header of a backup file (feature B2). Everything in here is
 * metadata the importer needs *before* it can decrypt: format/app/DB
 * versions for the version gates, the section list for the summary, and the
 * KDF parameters for key derivation. It deliberately contains no user
 * content — no counts of private data beyond section names, no titles, no
 * keys. The salt and IV are not secret; the passphrase is the only secret.
 */
@Serializable
data class BackupHeader(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val dbVersion: Int,
    val kdfAlgorithm: String,
    val kdfIterations: Int,
    val saltBase64: String,
    val ivBase64: String,
    val sections: List<String>
)

/** Why a backup file could not be produced or opened. */
sealed class BackupCryptoException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The bytes are not an OpenDroid backup at all, or are truncated/corrupt before the payload. */
    class InvalidFormat(message: String, cause: Throwable? = null) :
        BackupCryptoException(message, cause)

    /**
     * The GCM authentication tag did not verify. A wrong passphrase and a
     * tampered/corrupted payload are cryptographically indistinguishable —
     * both land here, and both must abort the restore with existing data
     * untouched.
     */
    class AuthenticationFailed(cause: Throwable? = null) :
        BackupCryptoException(
            "The password is wrong or the backup file is damaged.", cause
        )

    /** The file was written by a newer format than this build understands. */
    class UnsupportedVersion(message: String) : BackupCryptoException(message)
}

/**
 * Encryption for the portable backup file (feature B2).
 *
 * The AndroidKeyStore key in `KeystoreSecretStorage` is device-bound and
 * non-exportable by design, so a backup the user can carry to another
 * device cannot use it. Instead the key is derived from a user passphrase:
 *
 * - KDF: PBKDF2-HmacSHA256, [DEFAULT_ITERATIONS] (OWASP's current
 *   recommendation for PBKDF2-HMAC-SHA256), 16-byte random salt.
 * - Cipher: AES-256-GCM (AEAD), 12-byte random IV, 128-bit tag.
 *
 * File layout:
 * ```
 * [4 bytes magic "ODB1"][1 byte layout version][4 bytes BE header length]
 * [header JSON, UTF-8][AES-GCM ciphertext …]
 * ```
 *
 * Decryption streams through [Cipher.update] chunk by chunk and only
 * succeeds when the final `doFinal()` verifies the GCM tag — a wrong
 * passphrase or a single flipped bit anywhere in the payload throws
 * [BackupCryptoException.AuthenticationFailed], so callers can validate a
 * backup completely (decrypt to a temp file) before touching live data.
 *
 * Pure JVM (no Android types, `java.util.Base64` like [com.opendroid.ai.core.security.PinHasher])
 * so the format and failure modes are unit-testable off-device.
 */
object BackupCrypto {

    /** Backup *format* version written by this build (the payload schema version). */
    const val FORMAT_VERSION = 1

    /** OWASP's current PBKDF2-HmacSHA256 work factor for password-based keys. */
    const val DEFAULT_ITERATIONS = 600_000

    /**
     * Highest header-supplied iteration count [decrypt] will honour. The
     * header is attacker-controlled (backups are opened from untrusted
     * sources), so an unbounded value would send PBKDF2 into an hours-long
     * derivation on open — a hang/DoS (audit-4 #4). Well above any work
     * factor this app writes ([DEFAULT_ITERATIONS]) or plausibly will.
     */
    private const val MAX_KDF_ITERATIONS = 2_000_000

    /** Shortest passphrase the backup flow accepts (enforced again in the UI). */
    const val MIN_PASSPHRASE_LENGTH = 10

    private const val LAYOUT_VERSION: Byte = 1
    private const val MAGIC = "ODB1"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val CHUNK_BYTES = 64 * 1024
    private const val MAX_HEADER_BYTES = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Writes the header for [metadata] (salt/IV/KDF fields are generated
     * here and overwrite whatever the caller put there) followed by the
     * encrypted form of whatever [writePlaintext] streams into the given
     * output. The passphrase chars are wiped from the spec after use.
     */
    fun encrypt(
        passphrase: CharArray,
        metadata: BackupHeader,
        output: OutputStream,
        iterations: Int = DEFAULT_ITERATIONS,
        writePlaintext: (OutputStream) -> Unit
    ) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Backup password must be at least $MIN_PASSPHRASE_LENGTH characters."
        }
        require(iterations > 0) { "KDF iterations must be positive." }
        require(iterations <= MAX_KDF_ITERATIONS) { "KDF iterations exceed the supported maximum." }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val header = metadata.copy(
            formatVersion = FORMAT_VERSION,
            kdfAlgorithm = KDF_ALGORITHM,
            kdfIterations = iterations,
            saltBase64 = java.util.Base64.getEncoder().encodeToString(salt),
            ivBase64 = java.util.Base64.getEncoder().encodeToString(iv)
        )
        val headerBytes = json.encodeToString(BackupHeader.serializer(), header)
            .toByteArray(Charsets.UTF_8)
        require(headerBytes.size <= MAX_HEADER_BYTES) { "Backup header too large." }

        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(LAYOUT_VERSION.toInt())
        output.write(headerBytes.size.toBigEndianBytes())
        output.write(headerBytes)

        val key = deriveKey(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val cipherSink = object : OutputStream() {
                private val buffer = ByteArray(CHUNK_BYTES)
                private var filled = 0

                override fun write(b: Int) {
                    buffer[filled++] = b.toByte()
                    if (filled == buffer.size) flushBuffer()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    var offset = off
                    var remaining = len
                    while (remaining > 0) {
                        val take = minOf(remaining, buffer.size - filled)
                        System.arraycopy(b, offset, buffer, filled, take)
                        filled += take
                        offset += take
                        remaining -= take
                        if (filled == buffer.size) flushBuffer()
                    }
                }

                private fun flushBuffer() {
                    if (filled == 0) return
                    val encrypted = cipher.update(buffer, 0, filled)
                    if (encrypted != null && encrypted.isNotEmpty()) output.write(encrypted)
                    filled = 0
                }

                fun finish() {
                    flushBuffer()
                    val tail = try {
                        cipher.doFinal()
                    } catch (failure: Exception) {
                        throw BackupCryptoException.InvalidFormat(
                            "Encryption failed.", failure
                        )
                    }
                    if (tail.isNotEmpty()) output.write(tail)
                }
            }
            writePlaintext(cipherSink)
            cipherSink.finish()
            output.flush()
        } finally {
            destroyQuietly(key)
        }
    }

    /**
     * Reads the header, derives the key from [passphrase] and streams the
     * decrypted payload into [plaintextOut]. Returns the header only after
     * the GCM tag has verified — if this function throws, whatever was
     * written to [plaintextOut] is unverified garbage and must be discarded
     * (callers decrypt to a temp file for exactly this reason).
     */
    fun decrypt(
        passphrase: CharArray,
        input: InputStream,
        plaintextOut: OutputStream
    ): BackupHeader {
        val header = readHeader(input)
        val salt = decodeBase64(header.saltBase64, "salt")
        val iv = decodeBase64(header.ivBase64, "IV")
        // The header is untrusted input: enforce the exact salt/IV sizes
        // encrypt() writes, and cap the KDF work factor before deriving —
        // otherwise a crafted file hangs the open for hours or fails with
        // a raw provider exception outside the typed taxonomy (audit-4 #4).
        if (salt.size != SALT_BYTES || iv.size != IV_BYTES) {
            throw BackupCryptoException.InvalidFormat("Backup header has a damaged salt or IV.")
        }
        if (header.kdfIterations <= 0 || header.kdfIterations > MAX_KDF_ITERATIONS) {
            throw BackupCryptoException.InvalidFormat("Backup header has invalid KDF parameters.")
        }
        val key = deriveKey(passphrase, salt, header.kdfIterations)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val plain = cipher.update(buffer, 0, read)
                if (plain != null && plain.isNotEmpty()) plaintextOut.write(plain)
            }
            val tail = try {
                cipher.doFinal()
            } catch (failure: AEADBadTagException) {
                throw BackupCryptoException.AuthenticationFailed(failure)
            } catch (failure: Exception) {
                throw BackupCryptoException.AuthenticationFailed(failure)
            }
            if (tail.isNotEmpty()) plaintextOut.write(tail)
            plaintextOut.flush()
        } finally {
            destroyQuietly(key)
        }
        return header
    }

    /** Reads and validates only the plaintext header; the payload is not touched. */
    fun readHeader(input: InputStream): BackupHeader {
        val magic = ByteArray(4)
        readFully(input, magic)
        if (!magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) {
            throw BackupCryptoException.InvalidFormat("This is not an OpenDroid backup file.")
        }
        val layout = input.read()
        if (layout < 0) throw BackupCryptoException.InvalidFormat("Backup file is truncated.")
        if (layout.toByte() != LAYOUT_VERSION) {
            throw BackupCryptoException.UnsupportedVersion(
                "This backup uses a newer file layout than this version of OpenDroid understands."
            )
        }
        val lengthBytes = ByteArray(4)
        readFully(input, lengthBytes)
        val headerLength = lengthBytes.toIntBigEndian()
        if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES) {
            throw BackupCryptoException.InvalidFormat("Backup header is damaged.")
        }
        val headerBytes = ByteArray(headerLength)
        readFully(input, headerBytes)
        val header = try {
            json.decodeFromString(BackupHeader.serializer(), headerBytes.toString(Charsets.UTF_8))
        } catch (failure: Exception) {
            throw BackupCryptoException.InvalidFormat("Backup header is damaged.", failure)
        }
        if (header.formatVersion > FORMAT_VERSION) {
            throw BackupCryptoException.UnsupportedVersion(
                "This backup was made by a newer version of OpenDroid. Update the app to restore it."
            )
        }
        if (header.kdfAlgorithm != KDF_ALGORITHM) {
            throw BackupCryptoException.InvalidFormat("Backup uses an unknown key derivation scheme.")
        }
        return header
    }

    /**
     * Best-effort key hygiene: on some JVM providers [SecretKeySpec.destroy]
     * throws DestroyFailedException (the Destroyable default method).
     * Cleanup must never fail — or mask the outcome of — the crypto
     * operation itself, so failures here are deliberately ignored.
     */
    private fun destroyQuietly(key: SecretKeySpec) {
        try {
            key.destroy()
        } catch (_: Exception) {
            // Best effort only; the key material is dropped with the object.
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val raw = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            return SecretKeySpec(raw, KEY_ALGORITHM)
        } finally {
            spec.clearPassword()
        }
    }

    private fun decodeBase64(value: String, field: String): ByteArray = try {
        java.util.Base64.getDecoder().decode(value)
    } catch (failure: IllegalArgumentException) {
        throw BackupCryptoException.InvalidFormat("Backup header has a damaged $field.", failure)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw BackupCryptoException.InvalidFormat("Backup file is truncated.")
            offset += read
        }
    }

    private fun Int.toBigEndianBytes(): ByteArray = byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        this.toByte()
    )

    private fun ByteArray.toIntBigEndian(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or
            ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or
            (this[3].toInt() and 0xFF)
}
