package com.opendroid.ai.core.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAuthConfigTest {

    @Test
    fun `unconfigured when client id is null blank or whitespace`() {
        assertFalse(GoogleAuthConfig.isConfiguredValue(null))
        assertFalse(GoogleAuthConfig.isConfiguredValue(""))
        assertFalse(GoogleAuthConfig.isConfiguredValue("   "))
        assertFalse(GoogleAuthConfig.isConfiguredValue("\n\t"))
    }

    @Test
    fun `configured when any non-blank client id is present`() {
        assertTrue(
            GoogleAuthConfig.isConfiguredValue(
                "123456789012-abcdefghijklmnopqrstuvwxyz123456.apps.googleusercontent.com"
            )
        )
    }

    @Test
    fun `looksLikeWebClientId requires the googleusercontent suffix`() {
        assertTrue(
            GoogleAuthConfig.looksLikeWebClientId(
                "123456789012-abc.apps.googleusercontent.com"
            )
        )
        assertFalse(GoogleAuthConfig.looksLikeWebClientId("not-a-client-id"))
        assertFalse(GoogleAuthConfig.looksLikeWebClientId(""))
    }

    @Test
    fun `connect requests calendar only and mail adds gmail incrementally`() {
        assertEquals(listOf(GoogleScopes.CALENDAR_EVENTS), GoogleScopes.CONNECT_SCOPES)
        assertEquals(
            listOf(GoogleScopes.CALENDAR_EVENTS, GoogleScopes.GMAIL_READONLY),
            GoogleScopes.MAIL_SCOPES
        )
    }

    @Test
    fun `scope labels are human readable and unknown scopes pass through`() {
        assertTrue(GoogleScopes.labelFor(GoogleScopes.CALENDAR_EVENTS).contains("Calendar"))
        assertTrue(GoogleScopes.labelFor(GoogleScopes.GMAIL_READONLY).contains("Gmail"))
        assertEquals("https://example.com/scope", GoogleScopes.labelFor("https://example.com/scope"))
    }
}
