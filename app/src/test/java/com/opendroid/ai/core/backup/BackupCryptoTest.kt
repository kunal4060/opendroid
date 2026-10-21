package com.opendroid.ai.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val passphrase = "correct horse battery".toCharArray()
    private val wrongPassphrase = "tr0ubadour & 3 more".toCharArray()

    // Small iteration count keeps the suite fast; the header records the
    // count, and decryption honours whatever the header says. Production
    // exports always use BackupCrypto.DEFAULT_ITERATIONS.
    private val testIterations = 5_000

    private fun metadata() = BackupHeader(
        formatVersion = BackupCrypto.FORMAT_VERSION,
        createdAt = 1_761_000_000_000L,
        appVersion = "1.0.7",
        dbVersion = 10,
        kdfAlgorithm = "",
        kdfIterations = 0,
        saltBase64 = "",
        ivBase64 = "",
        sections = listOf("chats", "memories", "settings")
    )

    private fun encryptBytes(plaintext: ByteArray, password: CharArray = passphrase): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(password, metadata(), out, iterations = testIterations) { sink ->
            sink.write(plaintext)
        }
        return out.toByteArray()
    }

    private fun decryptBytes(fileBytes: ByteArray, password: CharArray = passphrase): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.decrypt(password, ByteArrayInputStream(fileBytes), out)
        return out.toByteArray()
    }

    @Test
    fun `round trip returns the exact plaintext and the header metadata`() {
        val plaintext = "{\"sessions\":[],\"secret\":\"a very private memory\"}"
            .toByteArray(Charsets.UTF_8)
        val fileBytes = encryptBytes(plaintext)

        val plainOut = ByteArrayOutputStream()
        val header = BackupCrypto.decrypt(
            passphrase, ByteArrayInputStream(fileBytes), plainOut
        )
        assertArrayEquals(plaintext, plainOut.toByteArray())
        assertEquals(1_761_000_000_000L, header.createdAt)
        assertEquals("1.0.7", header.appVersion)
        assertEquals(10, header.dbVersion)
        assertEquals(listOf("chats", "memories", "settings"), header.sections)
        assertEquals(testIterations, header.kdfIterations)
    }

    @Test
    fun `large payload spanning many chunks round trips`() {
        val plaintext = ByteArray(500_000) { (it % 251).toByte() }
        val fileBytes = encryptBytes(plaintext)
        assertArrayEquals(plaintext, decryptBytes(fileBytes))
    }

    @Test
    fun `empty payload round trips`() {
        val fileBytes = encryptBytes(ByteArray(0))
        assertArrayEquals(ByteArray(0), decryptBytes(fileBytes))
    }

    @Test
    fun `wrong password fails authentication and yields no usable plaintext`() {
        val fileBytes = encryptBytes("{\"memories\":[]}".toByteArray(Charsets.UTF_8))
        assertThrows(BackupCryptoException.AuthenticationFailed::class.java) {
            decryptBytes(fileBytes, wrongPassphrase)
        }
    }

    @Test
    fun `a single flipped byte in the ciphertext fails authentication`() {
        val fileBytes = encryptBytes("payload that must stay intact".toByteArray(Charsets.UTF_8))
        val tampered = fileBytes.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertThrows(BackupCryptoException.AuthenticationFailed::class.java) {
            decryptBytes(tampered)
        }
    }

    @Test
    fun `a single flipped byte in the middle of the payload fails authentication`() {
        val plaintext = ByteArray(10_000) { 0x41 }
        val fileBytes = encryptBytes(plaintext)
        val tampered = fileBytes.copyOf()
        val middle = tampered.size / 2
        tampered[middle] = (tampered[middle].toInt() xor 0x40).toByte()
        assertThrows(BackupCryptoException.AuthenticationFailed::class.java) {
            decryptBytes(tampered)
        }
    }

    @Test
    fun `encrypted output contains no readable plaintext marker`() {
        val marker = "PLAINTEXT-MARKER-must-never-appear-in-the-file"
        val fileBytes = encryptBytes("prefix $marker suffix".toByteArray(Charsets.UTF_8))
        val asText = String(fileBytes, Charsets.ISO_8859_1)
        assertFalse(asText.contains(marker))
        assertFalse(asText.contains("prefix"))
    }

    @Test
    fun `two exports of the same data differ because salt and IV are random`() {
        val plaintext = "same input".toByteArray(Charsets.UTF_8)
        val first = encryptBytes(plaintext)
        val second = encryptBytes(plaintext)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plaintext, decryptBytes(first))
        assertArrayEquals(plaintext, decryptBytes(second))
    }

    @Test
    fun `a file without the magic is rejected as an invalid format`() {
        val notABackup = "just some text file contents".toByteArray(Charsets.UTF_8)
        assertThrows(BackupCryptoException.InvalidFormat::class.java) {
            decryptBytes(notABackup)
        }
    }

    @Test
    fun `a truncated file is rejected as an invalid format`() {
        val fileBytes = encryptBytes("hello".toByteArray(Charsets.UTF_8))
        val truncatedHeader = fileBytes.copyOf(6)
        assertThrows(BackupCryptoException.InvalidFormat::class.java) {
            decryptBytes(truncatedHeader)
        }
        // Truncated payload: header intact, tag missing -> authentication failure, not a crash.
        val truncatedPayload = fileBytes.copyOf(fileBytes.size - 4)
        assertThrows(BackupCryptoException.AuthenticationFailed::class.java) {
            decryptBytes(truncatedPayload)
        }
    }

    @Test
    fun `header can be read without the password`() {
        val fileBytes = encryptBytes("x".toByteArray(Charsets.UTF_8))
        val header = BackupCrypto.readHeader(ByteArrayInputStream(fileBytes))
        assertEquals(BackupCrypto.FORMAT_VERSION, header.formatVersion)
        assertEquals("PBKDF2WithHmacSHA256", header.kdfAlgorithm)
        assertTrue(header.saltBase64.isNotEmpty())
        assertTrue(header.ivBase64.isNotEmpty())
    }

    @Test
    fun `short passwords are refused at encryption time`() {
        assertThrows(IllegalArgumentException::class.java) {
            encryptBytes("data".toByteArray(Charsets.UTF_8), "short".toCharArray())
        }
    }
}
