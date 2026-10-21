package com.opendroid.ai.core.google

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailJsonTest {

    private fun b64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private val listResponse = """
        {
          "messages": [
            {"id": "m1", "threadId": "t1"},
            {"id": "m2", "threadId": "t2"}
          ],
          "resultSizeEstimate": 2
        }
    """.trimIndent()

    private val metadataResponse = """
        {
          "id": "m1",
          "threadId": "t1",
          "labelIds": ["INBOX", "UNREAD"],
          "snippet": "Meet & Greet is here",
          "internalDate": "1761090600000",
          "payload": {
            "mimeType": "multipart/alternative",
            "headers": [
              {"name": "Subject", "value": "ACM Meet & Greet is Here!"},
              {"name": "From", "value": "Assistant Director - Clubs <asstdir.cac@vitap.ac.in>"},
              {"name": "Date", "value": "Wed, 21 Oct 2026 08:30:00 +0530"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `message refs parse id and thread pairs`() {
        val refs = GmailJson.parseMessageRefs(listResponse)
        assertEquals(listOf("m1" to "t1", "m2" to "t2"), refs)
    }

    @Test
    fun `empty list response yields no refs`() {
        assertEquals(emptyList<Pair<String, String>>(), GmailJson.parseMessageRefs("{}"))
    }

    @Test
    fun `summary maps headers snippet unread and internal date`() {
        val summary = GmailJson.parseSummary(metadataResponse)
        assertEquals("m1", summary?.id)
        assertEquals("ACM Meet & Greet is Here!", summary?.subject)
        assertEquals("Assistant Director - Clubs <asstdir.cac@vitap.ac.in>", summary?.from)
        assertEquals("Meet & Greet is here", summary?.snippet)
        assertEquals(1761090600000L, summary?.dateMillis)
        assertTrue(summary?.unread == true)
    }

    @Test
    fun `summary without unread label is read and missing headers get placeholders`() {
        val body = """
            {"id": "m9", "threadId": "t9", "labelIds": ["INBOX"], "snippet": "",
             "payload": {"mimeType": "text/plain", "headers": []}}
        """.trimIndent()
        val summary = GmailJson.parseSummary(body)
        assertFalse(summary?.unread == true)
        assertEquals("(no subject)", summary?.subject)
        assertEquals("(unknown sender)", summary?.from)
        assertNull(summary?.dateMillis)
    }

    @Test
    fun `header lookup is case insensitive`() {
        val body = """
            {"id": "m3", "threadId": "t3", "snippet": "s",
             "payload": {"headers": [{"name": "subject", "value": "Lowercase works"}]}}
        """.trimIndent()
        assertEquals("Lowercase works", GmailJson.parseSummary(body)?.subject)
    }

    @Test
    fun `detail extracts the nested text plain part`() {
        val body = """
            {
              "id": "m1", "threadId": "t1", "snippet": "Hello D6",
              "internalDate": "1761090600000",
              "payload": {
                "mimeType": "multipart/alternative",
                "headers": [
                  {"name": "Subject", "value": "Hi"},
                  {"name": "From", "value": "a@b.c"},
                  {"name": "To", "value": "me@x.y"}
                ],
                "parts": [
                  {"mimeType": "text/plain", "body": {"data": "${b64Url("Hello D6 plain body")}"}},
                  {"mimeType": "text/html", "body": {"data": "${b64Url("<p>Hello D6 html</p>")}"}}
                ]
              }
            }
        """.trimIndent()
        val detail = GmailJson.parseDetail(body)
        assertEquals("Hello D6 plain body", detail?.bodyText)
        assertEquals("me@x.y", detail?.to)
        assertEquals("a@b.c", detail?.from)
    }

    @Test
    fun `detail falls back to stripped html when no plain part exists`() {
        val body = """
            {
              "id": "m2", "threadId": "t2", "snippet": "",
              "payload": {
                "mimeType": "text/html",
                "headers": [{"name": "Subject", "value": "Html only"}],
                "body": {"data": "${b64Url("<p>Hi <b>there</b></p>")}"}
              }
            }
        """.trimIndent()
        assertEquals("Hi there", GmailJson.parseDetail(body)?.bodyText)
    }

    @Test
    fun `base64url decoding handles missing padding and url-safe alphabet`() {
        // "Hello D6" with the standard padding stripped.
        assertEquals("Hello D6", GmailJson.decodeBase64Url("SGVsbG8gRDY"))
        // "~~~" encodes with '+' in the standard alphabet -> '-' url-safe.
        assertEquals("~~~", GmailJson.decodeBase64Url("fn5-"))
        // Garbage decodes to empty rather than throwing.
        assertEquals("", GmailJson.decodeBase64Url("!!!not-base64!!!"))
    }
}
