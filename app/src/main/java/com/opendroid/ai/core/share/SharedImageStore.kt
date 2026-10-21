package com.opendroid.ai.core.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the app-private copies of images that arrive through shares (C2).
 *
 * A share hands us a content URI plus a temporary read grant that dies with
 * the sending activity (and always dies at process death). Copying the bytes
 * into filesDir the moment the share is accepted is what makes a shared image
 * survive long enough to be attached to the user's next message; the copy is
 * deleted once that send consumes it, or when the user removes the pending
 * attachment chip.
 *
 * All functions do file IO — call them off the main thread.
 */
@Singleton
class SharedImageStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val inboxDir: File
        get() = File(context.filesDir, INBOX_DIR).apply { mkdirs() }

    /**
     * Copies the image at [uri] into private storage and returns its ref, or
     * null when the URI is not an image, cannot be read under the share grant,
     * or exceeds the size cap. [declaredMimeType] is the share intent's
     * declared type, used only when the provider reports no type itself.
     */
    fun importImage(uri: Uri, declaredMimeType: String?): SharedImageRef? {
        val resolver = context.contentResolver
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull() ?: declaredMimeType
        if (mimeType?.startsWith("image/") != true) return null

        val displayName = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "shared-image"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: displayName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }
            ?: "img"
        val target = File(inboxDir, "${UUID.randomUUID()}.$extension")
        return try {
            val copied = resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (copied <= 0L || copied > MAX_IMAGE_BYTES) {
                target.delete()
                return null
            }
            SharedImageRef(file = target, displayName = displayName, mimeType = mimeType)
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** Base64 of the private copy, in the shape the providers' imageBase64 channel expects. */
    fun loadBase64(ref: SharedImageRef): String? = try {
        if (!ref.file.exists()) {
            null
        } else {
            Base64.encodeToString(ref.file.readBytes(), Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        null
    }

    /** Deletes the private copy; a missing file is not an error. */
    fun delete(ref: SharedImageRef) {
        runCatching { ref.file.delete() }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val INBOX_DIR = "shared_inbox"

        /** Comfortably above any phone photo; a guard against pathological shares, not a limit users hit. */
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    }
}
