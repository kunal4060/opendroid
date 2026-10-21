package com.opendroid.ai.core.research

import com.opendroid.ai.data.db.entities.ResearchSourceEntity

/**
 * Citation marker handling for research answers (feature D3).
 *
 * The model writes `[n]` markers into its answer; whether a marker means
 * anything is decided HERE, against the stored source rows — never by the
 * model. Two operations, both pure:
 *
 *  - [validate] runs inside the pipeline before the answer is persisted:
 *    any marker whose number has no source row in this run is stripped
 *    (and counted), so the stored answer can only cite sources that were
 *    actually fetched in this run.
 *  - [linkify] runs at render time: a marker that resolves to a stored
 *    source becomes a markdown link to that source's URL; a marker that
 *    doesn't resolve (shouldn't exist after validation — defence in
 *    depth) stays plain text and links nowhere.
 */
object CitationText {

    private val MARKER = Regex("""\[(\d{1,3})\]""")

    /** The set of citation numbers used in [text]. */
    fun citedPositions(text: String): Set<Int> =
        MARKER.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()

    data class Validation(val text: String, val strippedMarkers: Int)

    /**
     * Removes every `[n]` marker that does not resolve to one of
     * [sourceCount] stored sources. Resolving markers are kept verbatim —
     * they are the citation the user sees and taps.
     */
    fun validate(text: String, sourceCount: Int): Validation {
        var stripped = 0
        val cleaned = MARKER.replace(text) { match ->
            val n = match.groupValues[1].toIntOrNull()
            if (n != null && n in 1..sourceCount) {
                match.value
            } else {
                stripped++
                ""
            }
        }
        // A stripped marker can leave a doubled space behind; collapse the
        // exact artifact ("word  next") without touching intentional
        // spacing elsewhere. Line breaks are not matched by ' '.
        return Validation(cleaned.replace("  ", " "), stripped)
    }

    /**
     * Turns resolvable `[n]` markers in [text] into markdown links
     * `[[n]](url)` pointing at the stored source's URL. Unresolvable
     * markers are returned unchanged (plain text, no link).
     */
    fun linkify(text: String, sources: List<ResearchSourceEntity>): String {
        if (sources.isEmpty()) return text
        val byPosition = sources.associateBy { it.position }
        return MARKER.replace(text) { match ->
            val n = match.groupValues[1].toIntOrNull()
            val source = n?.let { byPosition[it] }
            if (source != null) "[[${n}]](${source.url})" else match.value
        }
    }

    /** SHA-256 hex of the exact source text shown to the model, for the audit row. */
    fun contentHash(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
