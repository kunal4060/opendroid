package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["sessionId"])]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val text: String,
    val sender: String, // "USER" or "AGENT"
    val timestamp: Long,
    val modelBadge: String? = null,
    val contactPickerData: String? = null,
    // Which chat history this message belongs to. See ChatSessionEntity.
    val sessionId: String,
    // Composer attachments (feature B1, DB v11): the attachment payload and
    // its type/name, so an attached image or file stays visible in history
    // after restart and a regenerate/retry replays exactly what was sent.
    // imageBase64 stays null for text attachments (their content is inlined
    // into [text] at send time) and for PDFs too large to keep (see
    // AttachmentProcessor's storage cap) - those render as a file chip.
    val imageBase64: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentName: String? = null
)
