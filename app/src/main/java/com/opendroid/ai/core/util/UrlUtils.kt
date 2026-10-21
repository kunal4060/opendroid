package com.opendroid.ai.core.util

/**
 * Normalizes user-entered base URLs for self-hosted / custom LLM endpoints
 * (Ollama, Copilot proxy, custom OpenAI-compatible servers).
 *
 * User input is often messy: a missing scheme ("192.168.1.5:11434"), trailing
 * slashes ("https://myserver.com/"), or surrounding whitespace. [formatBaseUrl]
 * cleans that up so callers can safely append a path (e.g. "$baseUrl/api/tags")
 * without producing a double slash or a scheme-less URL that OkHttp would reject.
 */
object UrlUtils {

    /**
     * Returns a normalized base URL (no trailing slash, scheme guaranteed) built
     * from [rawUrl]. If [rawUrl] is blank, [fallback] is normalized and returned
     * instead. If both are blank, returns an empty string rather than throwing,
     * so callers can decide how to handle "not configured".
     *
     * [defaultScheme] is the scheme prepended when the input carries none.
     * The default is "http" because LAN self-hosted servers (Ollama, Copilot
     * proxy) are typically plain HTTP. Callers whose endpoints carry
     * credentials to a cloud service (custom OpenAI-compatible, Opencode Go)
     * MUST pass "https": defaulting those to http silently downgrades the
     * Bearer API key and the full prompt to cleartext (Loop-2 audit Area 1,
     * Finding 4).
     */
    fun formatBaseUrl(rawUrl: String?, fallback: String = "", defaultScheme: String = "http"): String {
        val normalizedInput = normalize(rawUrl, defaultScheme)
        if (normalizedInput.isNotEmpty()) return normalizedInput
        return normalize(fallback, defaultScheme)
    }

    private fun normalize(url: String?, defaultScheme: String): String {
        var trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""

        // Strip whitespace that may appear mid-string from copy/paste mistakes.
        trimmed = trimmed.replace(" ", "")
        if (trimmed.isEmpty()) return ""

        val withScheme = if (trimmed.contains("://")) trimmed else "$defaultScheme://$trimmed"

        return withScheme.trimEnd('/')
    }
}
