package com.opendroid.ai.core.share

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedIntentParserTest {

    @Test
    fun `browser share parses url text and page title subject`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/article")
            putExtra(Intent.EXTRA_SUBJECT, "An Interesting Article")
        }
        val parsed = SharedIntentParser.parse(intent)
        assertNotNull(parsed)
        assertEquals("https://example.com/article", parsed!!.text)
        assertEquals("An Interesting Article", parsed.subject)
        assertTrue(parsed.streamUris.isEmpty())
        assertEquals("text/plain", parsed.declaredMimeType)
    }

    @Test
    fun `plain text share parses without subject`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "remember this")
        }
        val parsed = SharedIntentParser.parse(intent)!!
        assertEquals("remember this", parsed.text)
        assertNull(parsed.subject)
    }

    @Test
    fun `single image share captures the stream uri`() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        val parsed = SharedIntentParser.parse(intent)!!
        assertEquals(listOf(uri), parsed.streamUris)
        assertNull(parsed.text)
    }

    @Test
    fun `send multiple captures every stream uri`() {
        val uris = arrayListOf(
            Uri.parse("content://media/external/images/media/1"),
            Uri.parse("content://media/external/images/media/2"),
            Uri.parse("content://media/external/images/media/3")
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        val parsed = SharedIntentParser.parse(intent)!!
        assertEquals(uris.toList(), parsed.streamUris)
    }

    @Test
    fun `share with no extras parses to an empty share, not null`() {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        val parsed = SharedIntentParser.parse(intent)
        assertNotNull(parsed)
        assertNull(parsed!!.text)
        assertNull(parsed.subject)
        assertTrue(parsed.streamUris.isEmpty())
    }

    @Test
    fun `non share intents parse to null`() {
        assertNull(SharedIntentParser.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(SharedIntentParser.parse(Intent(Intent.ACTION_VIEW)))
        assertNull(SharedIntentParser.parse(null))
    }
}
