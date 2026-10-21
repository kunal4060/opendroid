package com.opendroid.ai.core.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A user-readable attachment failure; the composer surfaces it verbatim. */
class AttachmentException(message: String) : Exception(message)

/**
 * An attachment the user has picked but not yet sent. The bytes already live
 * in app-private storage ([localFile] under `filesDir/attachments`) - the
 * composer's pending state never holds a raw content URI, so a staged
 * attachment survives picker permission windows and process death of the
 * picker activity.
 */
data class PendingAttachment(
    val localFile: File,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long
) {
    val isImage: Boolean get() = VisionSupport.isImageMime(mimeType)
}

/**
 * The send-ready form of a [PendingAttachment], produced by
 * [AttachmentProcessor.prepare] for one concrete provider decision.
 *
 * [imageBase64] is the payload the provider receives; [persistedImageBase64]
 * is what gets written to Room history. They differ only for large PDFs sent
 * natively: those are send-once, because an ~11 MB base64 blob does not
 * belong in a chat row (the bubble then shows a "not kept" file chip).
 * [textToAppend] carries extracted file text the caller appends to the
 * outgoing message text.
 */
data class PreparedAttachment(
    val imageBase64: String? = null,
    val persistedImageBase64: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentName: String? = null,
    val textToAppend: String? = null
)

/**
 * Staging + preparation for composer attachments (feature B1).
 *
 * Pick-time: bytes are copied out of the picker URI into app-private storage
 * with an 8 MB cap and a type allowlist (images, PDF, text). Send-time: the
 * staged file is converted for the provider that will answer - images are
 * downscaled and JPEG-encoded (the same payload shape the screenshot path
 * produces), PDFs go native base64 or PdfBox text extraction, text files are
 * read and inlined under a token budget.
 */
@Singleton
class AttachmentProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val attachmentsDir: File
        get() = File(context.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }

    /**
     * Copies the picked [uri] into app-private storage and returns its
     * staged form. Throws [AttachmentException] with a user-readable message
     * for oversize or unsupported files; the partial copy is removed.
     */
    suspend fun stagePickedFile(uri: Uri): PendingAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            )
            ?: "application/octet-stream"
        if (!isSupportedAttachmentMime(mimeType)) {
            throw AttachmentException(
                "That file type isn't supported. Attach an image, a PDF, or a text file."
            )
        }
        val displayName = queryDisplayName(uri) ?: "Attachment"
        val target = File(attachmentsDir, stagedFileName(mimeType, displayName))
        try {
            val size = resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) {
                            throw AttachmentException(
                                "That file is over the 8 MB attachment limit."
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    total
                }
            } ?: throw AttachmentException("Couldn't read the picked file.")
            PendingAttachment(target, mimeType, displayName, size)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /**
     * Creates the empty file a camera capture should write into, plus the
     * FileProvider URI to hand the camera app. The capture lands directly in
     * app-private storage, so no copy step is needed afterwards.
     */
    fun createCameraTarget(): Pair<File, Uri> {
        val file = File(attachmentsDir, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return file to uri
    }

    /** Wraps a finished camera capture; null when the capture wrote nothing. */
    fun finishCameraCapture(file: File): PendingAttachment? {
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return null
        }
        val name = "Photo ${java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))}.jpg"
        return PendingAttachment(file, "image/jpeg", name, file.length())
    }

    /** Best-effort removal of a staged file (chip removed / message sent). */
    fun discard(pending: PendingAttachment) {
        runCatching { pending.localFile.delete() }
    }

    /**
     * Converts a staged attachment for sending, following the routing
     * [decision] (see [VisionSupport.decide]). Must not be called with a
     * Blocked decision - the composer gate resolves those with the user
     * first. Throws [AttachmentException] when the file cannot be processed
     * (e.g. an encrypted or corrupt PDF); nothing is sent in that case.
     */
    suspend fun prepare(
        pending: PendingAttachment,
        decision: VisionSupport.AttachmentDecision
    ): PreparedAttachment = withContext(Dispatchers.IO) {
        if (decision is VisionSupport.AttachmentDecision.Blocked) {
            throw AttachmentException(decision.reason)
        }
        val file = pending.localFile
        when {
            pending.isImage -> prepareImage(pending)
            VisionSupport.isPdfMime(pending.mimeType) -> {
                if (decision is VisionSupport.AttachmentDecision.Native) {
                    val bytes = file.readBytes()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    PreparedAttachment(
                        imageBase64 = base64,
                        // Only PDFs up to the storage cap are kept in history;
                        // larger ones are sent once and shown as a file chip.
                        persistedImageBase64 =
                            if (bytes.size <= MAX_STORED_PDF_BYTES) base64 else null,
                        attachmentMimeType = "application/pdf",
                        attachmentName = pending.displayName
                    )
                } else {
                    val text = extractPdfText(file)
                    PreparedAttachment(
                        attachmentMimeType = "application/pdf",
                        attachmentName = pending.displayName,
                        textToAppend = fencedAttachmentBlock(
                            pending.displayName, trimToTokenBudget(text)
                        )
                    )
                }
            }
            else -> {
                // Text route (decide() only routes text/* and textual
                // application types here).
                val raw = file.readText(Charsets.UTF_8)
                PreparedAttachment(
                    attachmentMimeType = pending.mimeType,
                    attachmentName = pending.displayName,
                    textToAppend = fencedAttachmentBlock(
                        pending.displayName, trimToTokenBudget(raw)
                    )
                )
            }
        }
    }

    private fun prepareImage(pending: PendingAttachment): PreparedAttachment {
        val source = ImageDecoder.createSource(pending.localFile)
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = scaledDimensions(info.size.width, info.size.height, MAX_IMAGE_EDGE)
                if (w != info.size.width || h != info.size.height) {
                    decoder.setTargetSize(w, h)
                }
            }
        } catch (e: Exception) {
            throw AttachmentException("Couldn't read that image: ${e.localizedMessage ?: "decode failed"}")
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return PreparedAttachment(
            imageBase64 = base64,
            persistedImageBase64 = base64,
            // The payload is re-encoded JPEG whatever the source format was;
            // the stored mime describes the payload, not the original file.
            attachmentMimeType = "image/jpeg",
            attachmentName = pending.displayName
        )
    }

    private fun extractPdfText(file: File): String {
        ensurePdfBoxInitialised()
        return try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) {
                    throw AttachmentException(
                        "That PDF is password-protected, so its text can't be read."
                    )
                }
                PDFTextStripper().getText(doc)
            }
        } catch (e: AttachmentException) {
            throw e
        } catch (e: Exception) {
            throw AttachmentException(
                "Couldn't read that PDF: ${e.localizedMessage ?: "it may be corrupt or password-protected"}"
            )
        }
    }

    private fun ensurePdfBoxInitialised() {
        synchronized(pdfBoxLock) {
            if (!pdfBoxInitialised) {
                PDFBoxResourceLoader.init(context)
                pdfBoxInitialised = true
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
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
    }

    private fun stagedFileName(mimeType: String, displayName: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 }
            ?: "bin"
        return "${UUID.randomUUID()}.$ext"
    }

    companion object {
        const val ATTACHMENTS_DIR = "attachments"

        /** Hard cap at pick time - keeps one attachment + history window
         *  safely under Gemini's and Claude's request-size ceilings. */
        const val MAX_FILE_BYTES = 8L * 1024 * 1024

        /** PDFs larger than this are sent natively but not kept in history. */
        const val MAX_STORED_PDF_BYTES = 4L * 1024 * 1024

        const val MAX_IMAGE_EDGE = 1024
        const val JPEG_QUALITY = 80

        /** Token budget for extracted file text inlined into a message. */
        const val MAX_FILE_TEXT_TOKENS = 6000

        private val pdfBoxLock = Any()
        @Volatile private var pdfBoxInitialised = false
    }
}

/** The pick-time allowlist: images, PDFs, and text-like files. */
fun isSupportedAttachmentMime(mimeType: String?): Boolean {
    if (mimeType == null) return false
    return VisionSupport.isImageMime(mimeType) ||
        VisionSupport.isPdfMime(mimeType) ||
        VisionSupport.isTextMime(mimeType)
}

/** Downscale-only dimensions: longest edge capped at [maxEdge], aspect kept. */
fun scaledDimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxEdge || longest == 0) return width to height
    val scale = maxEdge.toDouble() / longest.toDouble()
    return (width * scale).toInt().coerceAtLeast(1) to
        (height * scale).toInt().coerceAtLeast(1)
}

/**
 * Trims extracted file text to the [AttachmentProcessor.MAX_FILE_TEXT_TOKENS]
 * budget (per [PromptBudget]'s estimator), appending an explicit truncation
 * note when content was dropped - never a silent cut.
 */
fun trimToTokenBudget(
    text: String,
    maxTokens: Int = AttachmentProcessor.MAX_FILE_TEXT_TOKENS
): String {
    if (PromptBudget.estimateTokens(text) <= maxTokens) return text
    // Proportional cut by the chars-per-token estimate, then walk down until
    // the estimator agrees (bounded iterations; the estimate is linear in
    // length, so the first cut is already close).
    var end = (text.length.toLong() * maxTokens /
        PromptBudget.estimateTokens(text)).toInt().coerceIn(1, text.length)
    var candidate = text.substring(0, end)
    var guard = 0
    while (PromptBudget.estimateTokens(candidate) > maxTokens && guard < 8) {
        end = (end * 0.9).toInt().coerceAtLeast(1)
        candidate = text.substring(0, end)
        guard++
    }
    return candidate.trimEnd() + "\n[Attachment truncated to fit the message size limit.]"
}

/** The fenced block extracted file text is inlined as, headed by its name. */
fun fencedAttachmentBlock(displayName: String, extractedText: String): String =
    "\n\n[Attached file: $displayName]\n```\n$extractedText\n```"
