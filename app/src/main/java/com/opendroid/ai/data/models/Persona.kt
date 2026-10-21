package com.opendroid.ai.data.models

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * What a library entry does when it is picked (feature B6). One library and one
 * UI hold both kinds; they differ only at apply-time:
 * - [PERSONA] entries are system prompts bound to a chat — the bound persona's
 *   prompt is prepended to the system prompt of that chat's LLM requests.
 * - [QUICK_COMMAND] entries are reusable text snippets — picking one inserts
 *   its text into the composer (never auto-sent, never bound to a chat).
 */
@Serializable
enum class PersonaKind {
    PERSONA,
    QUICK_COMMAND
}

/**
 * One entry of the prompt / persona library (feature B6).
 *
 * Persisted as JSON by `PersonaRepository` in its own DataStore — deliberately
 * NOT a Room entity, so the library needs no schema migration. [isBuiltIn]
 * only marks the seeded starters so the UI can badge them; built-ins are
 * ordinary rows in every other respect (editable, duplicable, deletable).
 */
@Serializable
data class Persona(
    val id: String,
    val name: String,
    val prompt: String,
    val kind: PersonaKind = PersonaKind.PERSONA,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** Input rules for library entries, shared by the repository, import and the editor UI. */
object PersonaValidation {
    const val MAX_NAME_CHARS = 60

    /**
     * Cap on prompt length: a persona is prepended to every request of a bound
     * chat, so an unbounded persona could crowd out the actual context on
     * on-device models with small prompt budgets.
     */
    const val MAX_PROMPT_CHARS = 4000

    /** Trims and validates a name; throws [IllegalArgumentException] when blank. */
    fun normalizedName(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Name must not be blank" }
        return trimmed.take(MAX_NAME_CHARS)
    }

    /** Trims and validates prompt/snippet text; throws [IllegalArgumentException] when blank or over the cap. */
    fun normalizedPrompt(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Prompt must not be blank" }
        require(trimmed.length <= MAX_PROMPT_CHARS) {
            "Prompt is ${trimmed.length} characters; the limit is $MAX_PROMPT_CHARS"
        }
        return trimmed
    }
}

/**
 * The single place where a bound persona meets a system prompt (feature B6).
 *
 * The persona goes BEFORE the base prompt: the persona sets the voice and the
 * task-specific stance, and the app's own behaviour/safety instructions that
 * follow still read as the operative rules. A null or blank persona returns
 * the base prompt untouched, so an unbound chat behaves exactly as before B6.
 */
object PersonaPromptComposer {
    fun compose(personaPrompt: String?, basePrompt: String): String {
        val persona = personaPrompt?.trim().orEmpty()
        if (persona.isEmpty()) return basePrompt
        return persona + "\n\n" + basePrompt
    }
}

/**
 * Merges library entries parsed from an import payload into the existing
 * library. Pure function so import behaviour is testable without storage.
 *
 * Rules: entries whose name or prompt fails [PersonaValidation] are skipped;
 * an imported id that is blank or already taken (by the existing library or
 * by an earlier imported entry) is replaced with a fresh id from [idFactory],
 * so an import can never overwrite or shadow an existing entry; imported
 * entries are never marked built-in.
 */
fun mergeImportedPersonas(
    existing: List<Persona>,
    imported: List<Persona>,
    idFactory: () -> String = { UUID.randomUUID().toString() },
    now: Long = System.currentTimeMillis()
): List<Persona> {
    val takenIds = existing.mapTo(HashSet()) { it.id }
    val merged = existing.toMutableList()
    for (entry in imported) {
        val name = runCatching { PersonaValidation.normalizedName(entry.name) }.getOrNull()
            ?: continue
        val prompt = runCatching { PersonaValidation.normalizedPrompt(entry.prompt) }.getOrNull()
            ?: continue
        var id = entry.id.trim()
        if (id.isEmpty() || id in takenIds) id = idFactory()
        takenIds += id
        merged += Persona(
            id = id,
            name = name,
            prompt = prompt,
            kind = entry.kind,
            isBuiltIn = false,
            createdAt = if (entry.createdAt > 0L) entry.createdAt else now,
            updatedAt = now
        )
    }
    return merged
}

/** Serializable payload of a library export (feature B6) — the file the user shares / re-imports. */
@Serializable
data class PersonaLibraryExport(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    val personas: List<Persona> = emptyList()
) {
    companion object {
        const val FORMAT = "opendroid-persona-library"
        const val CURRENT_VERSION = 1
    }
}

/**
 * The starter entries seeded on first run (feature B6). Real, functional
 * prompts the user can apply as-is — and editable/deletable like any other
 * row. Ids are fixed constants so the seed is identical on every read until
 * the user mutates the library (the first mutation persists the full list,
 * after which the stored list — not this seed — is authoritative, so a
 * deleted starter never resurrects).
 */
object PersonaStarters {
    const val CONCISE_ID = "starter-concise-answers"
    const val CODE_REVIEWER_ID = "starter-code-reviewer"
    const val STUDY_BUDDY_ID = "starter-study-buddy"
    const val STEP_BY_STEP_ID = "starter-quick-step-by-step"
    const val SUMMARISE_ID = "starter-quick-summarise"

    fun starters(): List<Persona> = listOf(
        Persona(
            id = CONCISE_ID,
            name = "Concise Answers",
            prompt = "Answer briefly and directly. Prefer short sentences and compact " +
                "lists over long explanations. Skip greetings, filler and repetition. " +
                "If a short answer would lose something important, add one short note " +
                "saying what was left out.",
            kind = PersonaKind.PERSONA,
            isBuiltIn = true
        ),
        Persona(
            id = CODE_REVIEWER_ID,
            name = "Code Reviewer",
            prompt = "You are a meticulous code reviewer. When shown code, look first " +
                "for bugs and edge cases, then for readability and performance issues. " +
                "Refer to the exact lines you mean, explain why each issue matters, and " +
                "suggest a concrete fix. End with a short summary of the most important " +
                "change to make.",
            kind = PersonaKind.PERSONA,
            isBuiltIn = true
        ),
        Persona(
            id = STUDY_BUDDY_ID,
            name = "Study Buddy",
            prompt = "You are a patient study partner. Explain concepts step by step in " +
                "simple language, check understanding with one short question at a " +
                "time, and use small everyday examples before formal definitions. If I " +
                "get something wrong, correct the specific misunderstanding instead of " +
                "repeating the whole explanation.",
            kind = PersonaKind.PERSONA,
            isBuiltIn = true
        ),
        Persona(
            id = STEP_BY_STEP_ID,
            name = "Step by step",
            prompt = "Explain this step by step, with a simple example:",
            kind = PersonaKind.QUICK_COMMAND,
            isBuiltIn = true
        ),
        Persona(
            id = SUMMARISE_ID,
            name = "Summarise",
            prompt = "Summarise the following in a few short bullet points:",
            kind = PersonaKind.QUICK_COMMAND,
            isBuiltIn = true
        )
    )
}
