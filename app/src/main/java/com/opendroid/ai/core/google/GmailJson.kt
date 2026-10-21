package com.opendroid.ai.core.google

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure mapping between Gmail API JSON and the D6 mail models. No Android or
 * network types here, so the mapping is unit-testable on the JVM.
 *
 * Quota discipline (from the D6 research): list first, then `format=metadata`
 * gets — never `format=full` for a whole list. The full body is fetched only
 * when the user opens one message.
 */
object GmailJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a `messages.list` response into (id, threadId) pairs. */
    fun parseMessageRefs(responseBody: String): List<Pair<String, String>> {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val messages = root["messages"]?.jsonArray ?: return emptyList()
        return messages.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj.string("id") ?: return@mapNotNull null
            id to (obj.string("threadId") ?: id)
        }
    }

    /** Parses a `messages.get?format=metadata` response into a summary row. */
    fun parseSummary(responseBody: String): GmailMessageSummary? {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val id = root.string("id") ?: return null
        val headers = headersOf(root)
        val labels = root["labelIds"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
            ?: emptySet()
        return GmailMessageSummary(
            id = id,
            threadId = root.string("threadId") ?: id,
            from = headerValue(headers, "From") ?: "(unknown sender)",
            subject = headerValue(headers, "Subject") ?: "(no subject)",
            dateMillis = root.string("internalDate")?.toLongOrNull(),
            snippet = root.string("snippet").orEmpty(),
            unread = "UNREAD" in labels
        )
    }

    /** Parses a `messages.get?format=full` response into the opened-message view. */
    fun parseDetail(responseBody: String): GmailMessageDetail? {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val id = root.string("id") ?: return null
        val headers = headersOf(root)
        val payload = root["payload"]?.jsonObject
        return GmailMessageDetail(
            id = id,
            threadId = root.string("threadId") ?: id,
            from = headerValue(headers, "From") ?: "(unknown sender)",
            to = headerValue(headers, "To").orEmpty(),
            subject = headerValue(headers, "Subject") ?: "(no subject)",
            dateMillis = root.string("internalDate")?.toLongOrNull(),
            snippet = root.string("snippet").orEmpty(),
            bodyText = payload?.let { extractTextBody(it) }.orEmpty()
        )
    }

    /**
     * Extracts the readable text of a message payload: the first text/plain
     * part (depth-first, the order Gmail nests alternatives in), falling back
     * to a tag-stripped text/html part. Returns "" when there is neither.
     */
    fun extractTextBody(payload: JsonObject): String {
        findPart(payload, "text/plain")?.let { return it }
        findPart(payload, "text/html")?.let { return stripHtml(it) }
        return ""
    }

    private fun findPart(part: JsonObject, mimeType: String): String? {
        val ownMime = part.string("mimeType")
        val data = part["body"]?.jsonObject?.string("data")
        if (ownMime == mimeType && data != null) {
            return decodeBase64Url(data)
        }
        val children = part["parts"]?.jsonArray ?: return null
        for (child in children) {
            findPart(child.jsonObject, mimeType)?.let { return it }
        }
        return null
    }

    /** Decodes Gmail's base64url body data (padding often omitted). */
    fun decodeBase64Url(data: String): String {
        val normalized = data.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            ""
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun headersOf(message: JsonObject): List<Pair<String, String>> {
        val payload = message["payload"]?.jsonObject ?: return emptyList()
        val headers = payload["headers"]?.jsonArray ?: return emptyList()
        return headers.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj.string("name") ?: return@mapNotNull null
            name to (obj.string("value").orEmpty())
        }
    }

    private fun headerValue(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        }
}
