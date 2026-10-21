package com.opendroid.ai.core.share

import java.io.File

/**
 * One image that arrived via a share, already copied into app-private storage.
 * The sender's content URI grant is temporary, so the private copy (see
 * [SharedImageStore]) is the only reference the rest of the app may hold.
 */
data class SharedImageRef(
    val file: File,
    val displayName: String,
    val mimeType: String?
)

/**
 * A normalised inbound share (feature C2): the text a user shared, the page
 * title a browser attached to it, and any shared images — reduced to a shape
 * the chat layer can stage without knowing anything about share intents.
 *
 * Shared content is *input*, never a command: it is staged as a prefilled
 * composer draft plus pending attachments, and nothing is sent or executed
 * until the user writes their own instruction and hits send.
 */
data class SharedContent(
    val text: String?,
    val subject: String?,
    val images: List<SharedImageRef>,
    val kind: Kind
) {
    enum class Kind { LINK, TEXT, IMAGES, MIXED, EMPTY }

    val isEmpty: Boolean get() = kind == Kind.EMPTY

    /**
     * The composer draft staged for this share. A browser share keeps BOTH the
     * page title and the URL (checklist requirement); plain text arrives
     * verbatim; image shares name their files so the transcript shows what was
     * attached even though the image bytes ride the message separately.
     */
    fun composerPrefill(): String = when (kind) {
        Kind.LINK -> if (subject != null) {
            "Shared link: \"$subject\" — $text"
        } else {
            "Shared link: $text"
        }
        Kind.TEXT -> text.orEmpty()
        Kind.IMAGES -> imageLines()
        Kind.MIXED -> text.orEmpty() + "\n" + imageLines()
        Kind.EMPTY -> ""
    }

    /** Title for the fresh chat a share lands in: page title, else text, else images. */
    fun suggestedSessionTitle(): String {
        val base = subject
            ?: text?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?: images.firstOrNull()?.let { "Shared image: ${it.displayName}" }
            ?: "Shared content"
        return base.trim().take(MAX_TITLE_LENGTH)
    }

    private fun imageLines(): String =
        images.joinToString("\n") { "Shared image: ${it.displayName}" }

    companion object {
        private const val MAX_TITLE_LENGTH = 48

        /** Classifies raw share parts; blank text/subject count as absent. */
        fun fromRaw(
            text: String?,
            subject: String?,
            images: List<SharedImageRef>
        ): SharedContent {
            val cleanText = text?.trim()?.takeIf { it.isNotEmpty() }
            val cleanSubject = subject?.trim()?.takeIf { it.isNotEmpty() }
            val kind = when {
                cleanText == null && images.isEmpty() -> Kind.EMPTY
                images.isNotEmpty() && cleanText == null -> Kind.IMAGES
                images.isNotEmpty() -> Kind.MIXED
                isSingleUrl(cleanText!!) -> Kind.LINK
                else -> Kind.TEXT
            }
            return SharedContent(cleanText, cleanSubject, images, kind)
        }

        /** True when [text] is exactly one http(s) URL and nothing else. */
        internal fun isSingleUrl(text: String): Boolean {
            if (text.any { it.isWhitespace() }) return false
            return text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true)
        }

        /**
         * Note appended to the sent message when several images were shared.
         * The message model carries a single image (imageBase64), so the first
         * shared image rides the message and the rest are named here — the
         * transcript stays honest about what the user shared instead of the
         * extra images vanishing silently. Full multi-image persistence is
         * B1's attachment work.
         */
        fun additionalImagesNote(images: List<SharedImageRef>): String? {
            if (images.size < 2) return null
            val rest = images.drop(1)
            val plural = if (rest.size == 1) "" else "s"
            return "[+${rest.size} more shared image$plural: " +
                rest.joinToString(", ") { it.displayName } + "]"
        }
    }
}
