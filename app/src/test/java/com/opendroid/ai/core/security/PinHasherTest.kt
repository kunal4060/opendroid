package com.opendroid.ai.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun `created record verifies the same pin`() {
        val record = PinHasher.createRecord("1234")
        assertTrue(PinHasher.verify("1234", record))
    }

    @Test
    fun `wrong pin does not verify`() {
        val record = PinHasher.createRecord("1234")
        assertFalse(PinHasher.verify("1235", record))
        assertFalse(PinHasher.verify("12345", record))
        assertFalse(PinHasher.verify("", record))
    }

    @Test
    fun `same pin produces different records because of the random salt`() {
        val first = PinHasher.createRecord("9876")
        val second = PinHasher.createRecord("9876")
        assertNotEquals(first, second)
        assertTrue(PinHasher.verify("9876", first))
        assertTrue(PinHasher.verify("9876", second))
    }

    @Test
    fun `malformed records never verify and never throw`() {
        assertFalse(PinHasher.verify("1234", ""))
        assertFalse(PinHasher.verify("1234", "garbage"))
        assertFalse(PinHasher.verify("1234", "v2\$600000\$AAAA\$BBBB"))
        assertFalse(PinHasher.verify("1234", "v1\$notanumber\$AAAA\$BBBB"))
        assertFalse(PinHasher.verify("1234", "v1\$-5\$AAAA\$BBBB"))
        assertFalse(PinHasher.verify("1234", "v1\$600000\$!!!invalid!!!\$BBBB"))
        assertFalse(PinHasher.verify("1234", "v1\$600000\$\$BBBB"))
        assertFalse(PinHasher.verify("1234", "v1\$600000\$AAAA\$"))
    }

    @Test
    fun `record naming an iteration count above the cap never verifies`() {
        // Swap a genuine record's count for an absurd one: verify must
        // reject it outright instead of starting a near-endless PBKDF2
        // derivation (this test returning at all is the assertion).
        val parts = PinHasher.createRecord("1234").split('$').toMutableList()
        parts[1] = "2000000000"
        assertFalse(PinHasher.verify("1234", parts.joinToString("$")))
        parts[1] = (PinHasher.MAX_ITERATIONS + 1).toString()
        assertFalse(PinHasher.verify("1234", parts.joinToString("$")))
    }

    @Test
    fun `pin format is four to six digits`() {
        assertFalse(PinHasher.isValidPinFormat(""))
        assertFalse(PinHasher.isValidPinFormat("123"))
        assertTrue(PinHasher.isValidPinFormat("1234"))
        assertTrue(PinHasher.isValidPinFormat("12345"))
        assertTrue(PinHasher.isValidPinFormat("123456"))
        assertFalse(PinHasher.isValidPinFormat("1234567"))
        assertFalse(PinHasher.isValidPinFormat("12a4"))
        assertFalse(PinHasher.isValidPinFormat("12 4"))
    }
}
