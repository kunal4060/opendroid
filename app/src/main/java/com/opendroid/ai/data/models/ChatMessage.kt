package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val text: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis(),
    val modelBadge: String? = null,
    val imageBase64: String? = null,
    val contactPickerData: String? = null,
    // Composer attachments (feature B1). [imageBase64] carries the binary
    // payload for image attachments (and for PDFs sent natively to providers
    // that accept them); these two fields say what that payload actually is
    // and what the user-facing file was called. Both null on every message
    // that predates attachments - a null [attachmentMimeType] with a non-null
    // [imageBase64] is the legacy screenshot shape and means image/jpeg.
    val attachmentMimeType: String? = null,
    val attachmentName: String? = null
) {
    enum class Sender {
        USER, AGENT
    }
}
