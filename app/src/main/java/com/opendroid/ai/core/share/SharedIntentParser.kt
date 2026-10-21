package com.opendroid.ai.core.share

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * The raw extras of an ACTION_SEND / ACTION_SEND_MULTIPLE intent, before any
 * normalisation. Image streams are still sender-owned content URIs here —
 * copying them into app-private storage is [SharedImageStore]'s job, done by
 * the consumer off the main thread.
 */
data class ParsedShare(
    val text: String?,
    val subject: String?,
    val streamUris: List<Uri>,
    val declaredMimeType: String?
)

/**
 * Turns a launch intent into a [ParsedShare]. Anything that is not a share
 * intent parses to null; a share intent with no usable extras parses to a
 * [ParsedShare] with all fields empty, so the caller can show an honest
 * "nothing was shared" state instead of crashing or silently doing nothing.
 */
object SharedIntentParser {

    fun isShareIntent(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_SEND_MULTIPLE

    fun parse(intent: Intent?): ParsedShare? {
        if (!isShareIntent(intent)) return null
        intent!!
        val streamUris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(singleStream(intent))
            Intent.ACTION_SEND_MULTIPLE -> multipleStreams(intent)
            else -> emptyList()
        }
        return ParsedShare(
            text = sharedText(intent),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
            streamUris = streamUris,
            declaredMimeType = intent.type
        )
    }

    /**
     * EXTRA_TEXT is a plain String for single shares; for SEND_MULTIPLE some
     * senders put a list of CharSequences there instead — accept both.
     */
    private fun sharedText(intent: Intent): String? {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { return it }
        val parts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
        } else {
            @Suppress("DEPRECATION")
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
        }
        return parts?.joinToString("\n") { it.toString() }
    }

    private fun singleStream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    private fun multipleStreams(intent: Intent): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return list.orEmpty().filterNotNull()
    }
}
