package com.opendroid.ai.core.google

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * D6 — Gmail API (read-only) over the app's shared OkHttp client.
 *
 * Search uses Gmail's own `q=` syntax verbatim ("from:priya", "is:unread",
 * "newer_than:7d" all work). Reads are metadata-first: [search] lists ids
 * then fetches headers/snippets per message (capped — Gmail quota punishes
 * naive list+get loops); [getMessage] is the only `format=full` call and
 * happens when the user opens one mail.
 *
 * This client is deliberately separate from the existing intent-based mail
 * actions (which open the Gmail app's compose/view UI): nothing here is
 * labelled API data unless it came from `gmail.googleapis.com` with the
 * OAuth token, and a 401 surfaces as [GoogleApiException.isUnauthorized] so
 * the caller can turn it into a reconnect prompt.
 */
@Singleton
class GmailApi @Inject constructor(
    private val httpClient: OkHttpClient
) {

    /**
     * Searches the connected mailbox (empty [query] = most recent mail) and
     * returns metadata summaries, newest first, capped at [maxResults].
     */
    suspend fun search(
        accessToken: String,
        query: String = "",
        maxResults: Int = MAX_RESULTS_CAP
    ): List<GmailMessageSummary> {
        val capped = maxResults.coerceIn(1, MAX_RESULTS_CAP)
        val listUrl = "$BASE_URL/users/me/messages".toHttpUrl().newBuilder()
            .addQueryParameter("maxResults", capped.toString())
            .apply { if (query.isNotBlank()) addQueryParameter("q", query) }
            .build()
        val listBody = executeForBody(
            Request.Builder()
                .url(listUrl)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        val refs = GmailJson.parseMessageRefs(listBody)
        return refs.mapNotNull { (id, _) ->
            val metadataUrl = "$BASE_URL/users/me/messages/$id".toHttpUrl().newBuilder()
                .addQueryParameter("format", "metadata")
                .addQueryParameter("metadataHeaders", "Subject")
                .addQueryParameter("metadataHeaders", "From")
                .addQueryParameter("metadataHeaders", "Date")
                .build()
            val metadataBody = executeForBody(
                Request.Builder()
                    .url(metadataUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            GmailJson.parseSummary(metadataBody)
        }
    }

    /** Fetches one message in full (headers + decoded body) for the reader view. */
    suspend fun getMessage(accessToken: String, messageId: String): GmailMessageDetail {
        val url = "$BASE_URL/users/me/messages/$messageId".toHttpUrl().newBuilder()
            .addQueryParameter("format", "full")
            .build()
        val body = executeForBody(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        return GmailJson.parseDetail(body)
            ?: throw GoogleApiException(200, "Gmail API returned an unparseable message.")
    }

    private suspend fun executeForBody(request: Request): String = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GoogleApiException(
                    response.code,
                    "Gmail API failed (HTTP ${response.code})."
                )
            }
            text
        }
    }

    companion object {
        private const val BASE_URL = "https://gmail.googleapis.com/gmail/v1"

        /** Hard cap on list+metadata fan-out per search (quota discipline). */
        const val MAX_RESULTS_CAP = 20
    }
}
