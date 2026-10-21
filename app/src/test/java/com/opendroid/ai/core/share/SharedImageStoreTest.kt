package com.opendroid.ai.core.share

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Exercises the private-copy path with file:// URIs: the store must copy the
 * bytes into filesDir (the sender grant behind a content:// URI is temporary),
 * hand back base64 of the copy, and delete the copy on request.
 */
@RunWith(RobolectricTestRunner::class)
class SharedImageStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SharedImageStore(context)

    @Test
    fun `import copies the image into private storage and loadBase64 round trips`() {
        val source = File(context.cacheDir, "photo.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val ref = store.importImage(Uri.fromFile(source), declaredMimeType = "image/png")
        assertNotNull(ref)
        ref!!
        assertTrue(ref.file.exists())
        assertTrue(ref.file.path.contains("shared_inbox"))
        assertEquals("photo.png", ref.displayName)
        assertEquals(
            Base64.encodeToString(byteArrayOf(1, 2, 3, 4, 5), Base64.NO_WRAP),
            store.loadBase64(ref)
        )
        store.delete(ref)
        assertFalse(ref.file.exists())
    }

    @Test
    fun `non image content is rejected`() {
        val source = File(context.cacheDir, "notes.txt").apply { writeText("hello") }
        val ref = store.importImage(Uri.fromFile(source), declaredMimeType = "text/plain")
        assertNull(ref)
    }

    @Test
    fun `unreadable uri returns null instead of throwing`() {
        val missing = File(context.cacheDir, "does-not-exist.png")
        val ref = store.importImage(Uri.fromFile(missing), declaredMimeType = "image/png")
        assertNull(ref)
    }
}
