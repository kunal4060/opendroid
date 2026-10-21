package com.opendroid.ai.core.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalApiTokenTest {

    @Test
    fun `generated tokens are 32 url-safe characters`() {
        val token = ExternalApiToken.generate()
        assertEquals(32, token.length)
        assertTrue(ExternalApiToken.isPlausibleFormat(token))
    }

    @Test
    fun `generated tokens differ between calls`() {
        assertNotEquals(ExternalApiToken.generate(), ExternalApiToken.generate())
    }

    @Test
    fun `verify accepts the token a record was created from`() {
        val token = ExternalApiToken.generate()
        val record = ExternalApiToken.createRecord(token)
        assertTrue(ExternalApiToken.verify(token, record))
    }

    @Test
    fun `verify rejects a different token`() {
        val record = ExternalApiToken.createRecord(ExternalApiToken.generate())
        assertFalse(ExternalApiToken.verify(ExternalApiToken.generate(), record))
    }

    @Test
    fun `verify rejects a token with one character changed`() {
        val token = ExternalApiToken.generate()
        val record = ExternalApiToken.createRecord(token)
        val tampered = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'
        assertFalse(ExternalApiToken.verify(tampered, record))
    }

    @Test
    fun `the record never contains the token itself`() {
        val token = ExternalApiToken.generate()
        val record = ExternalApiToken.createRecord(token)
        assertFalse(record.contains(token))
    }

    @Test
    fun `records are salted - same token yields different records`() {
        val token = ExternalApiToken.generate()
        assertNotEquals(
            ExternalApiToken.createRecord(token),
            ExternalApiToken.createRecord(token)
        )
    }

    @Test
    fun `verify rejects malformed records without throwing`() {
        val token = ExternalApiToken.generate()
        assertFalse(ExternalApiToken.verify(token, ""))
        assertFalse(ExternalApiToken.verify(token, "garbage"))
        assertFalse(ExternalApiToken.verify(token, "v2\$AAAA\$BBBB"))
        assertFalse(ExternalApiToken.verify(token, "v1\$not-base64!\$also-not!"))
        assertFalse(ExternalApiToken.verify(token, "v1\$\$"))
    }

    @Test
    fun `verify rejects implausible token shapes without hashing`() {
        val record = ExternalApiToken.createRecord(ExternalApiToken.generate())
        assertFalse(ExternalApiToken.verify("", record))
        assertFalse(ExternalApiToken.verify("short", record))
        assertFalse(ExternalApiToken.verify("has spaces in it........", record))
    }

    @Test
    fun `hint is the last four characters`() {
        assertEquals("wxyz", ExternalApiToken.hintFor("abcdefghijklmnopqrstuvwxwxyz"))
    }
}
