package com.opendroid.ai.core.learning

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A user-taught meaning for an action name the planner invented but the app
 * does not implement (B5 — unknown-action learning loop).
 *
 * Mappings are keyed by the *normalised* attempted verb (see
 * [normalizeLearnedActionKey]) and persisted as one JSON document in DataStore
 * by [LearnedActionStore] — deliberately NOT a Room table, so this feature
 * needs no schema migration. The review state shown in the UI is derived from
 * the mapping: no mapping = PENDING, kind DISMISSED = dismissed, kind
 * ACTION/MACRO = converted.
 */
@Serializable
data class LearnedActionMapping(
    /** Normalised attempted verb this mapping answers for, e.g. "SEND_WHATSAPP_MSG". */
    val attemptedAction: String,
    val kind: Kind,
    /** Registered action to dispatch instead. Only for [Kind.ACTION]. */
    val targetAction: String? = null,
    /** Name of the macro to run instead. Only for [Kind.MACRO]. */
    val macroName: String? = null,
    /**
     * Parameter values for the target action (kind ACTION only). A value may
     * embed `{paramName}` tokens, which are substituted from the attempted
     * call's own parameters at dispatch time (see [LearnedParamTemplate]).
     */
    val paramTemplate: Map<String, String> = emptyMap(),
    val updatedAt: Long = 0L
) {
    enum class Kind { ACTION, MACRO, DISMISSED }
}

/** Review-queue state of one unknown-action group, derived from its mapping. */
enum class UnknownActionReviewState { PENDING, DISMISSED, CONVERTED }

fun LearnedActionMapping?.reviewState(): UnknownActionReviewState = when (this?.kind) {
    null -> UnknownActionReviewState.PENDING
    LearnedActionMapping.Kind.DISMISSED -> UnknownActionReviewState.DISMISSED
    LearnedActionMapping.Kind.ACTION, LearnedActionMapping.Kind.MACRO ->
        UnknownActionReviewState.CONVERTED
}

/**
 * Normalises a raw attempted verb into the mapping key. This MUST stay
 * behaviour-identical to `ActionAutoMapper.normalizeActionName`, because the
 * dispatcher looks mappings up by the name it already normalised that way.
 */
fun normalizeLearnedActionKey(raw: String): String {
    return raw.trim()
        .uppercase()
        .replace(Regex("[\\s]+"), "_")   // spaces → underscores
        .replace("-", "_")               // hyphens → underscores
        .replace(Regex("[^A-Z0-9_]"), "") // strip invalid chars
}

/**
 * Applies a taught parameter template to the parameters of one attempted call.
 *
 * `{token}` placeholders inside template values are replaced from
 * [runtimeParams] (matched case-insensitively, because planners are sloppy
 * about parameter casing). A template entry whose value still contains an
 * unresolved `{token}` after substitution is dropped entirely — dispatching a
 * literal "{contact}" to a real action would be worse than letting the
 * schema layer report the parameter as missing.
 */
object LearnedParamTemplate {
    private val tokenRegex = Regex("\\{([A-Za-z0-9_]+)\\}")

    fun apply(template: Map<String, String>, runtimeParams: Map<String, String>): Map<String, String> {
        if (template.isEmpty()) return emptyMap()
        val lookup = runtimeParams.entries.associate { (k, v) -> k.lowercase() to v }
        return template.mapNotNull { (key, rawValue) ->
            var unresolved = false
            val value = tokenRegex.replace(rawValue) { match ->
                val token = match.groupValues[1].lowercase()
                val replacement = lookup[token]
                if (replacement == null) {
                    unresolved = true
                    match.value
                } else {
                    replacement
                }
            }
            if (unresolved) null else key to value
        }.toMap()
    }

    /** Tokens a template references, e.g. for editor hints. Order-stable. */
    fun tokensIn(template: Map<String, String>): List<String> =
        template.values.flatMap { value -> tokenRegex.findAll(value).map { it.groupValues[1] } }
            .distinct()
}

/** What the dispatcher should do with an attempted call, per its mapping. */
sealed interface LearnedResolution {
    /** Run this registered action with these fully-resolved parameters. */
    data class RunAction(val targetAction: String, val params: Map<String, String>) : LearnedResolution

    /** Run the named macro through the normal RUN_MACRO handler. */
    data class RunMacro(val macroName: String) : LearnedResolution

    /** The user dismissed this verb: skip the step quietly. */
    data object Skip : LearnedResolution
}

/** Pure mapping → resolution decision, kept separate from the dispatcher for testing. */
fun LearnedActionMapping.resolve(runtimeParams: Map<String, String>): LearnedResolution? =
    when (kind) {
        LearnedActionMapping.Kind.DISMISSED -> LearnedResolution.Skip
        LearnedActionMapping.Kind.ACTION -> targetAction
            ?.takeIf { it.isNotBlank() }
            ?.let { LearnedResolution.RunAction(it, LearnedParamTemplate.apply(paramTemplate, runtimeParams)) }
        LearnedActionMapping.Kind.MACRO -> macroName
            ?.takeIf { it.isNotBlank() }
            ?.let { LearnedResolution.RunMacro(it) }
    }

/** JSON codec for the whole mapping table (verb key → mapping). */
object LearnedActionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(mappings: Map<String, LearnedActionMapping>): String =
        json.encodeToString(mappings)

    fun decode(raw: String?): Map<String, LearnedActionMapping> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, LearnedActionMapping>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
