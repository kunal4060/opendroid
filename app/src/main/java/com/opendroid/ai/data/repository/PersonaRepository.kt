package com.opendroid.ai.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opendroid.ai.data.models.Persona
import com.opendroid.ai.data.models.PersonaKind
import com.opendroid.ai.data.models.PersonaLibraryExport
import com.opendroid.ai.data.models.PersonaStarters
import com.opendroid.ai.data.models.PersonaValidation
import com.opendroid.ai.data.models.mergeImportedPersonas
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Thrown by persona-library mutations when the stored library value
 * exists but cannot be decoded. Mutations refuse in this state (instead
 * of writing over the unreadable value) so a corrupt store can never be
 * silently turned into permanent data loss by the next create/update.
 */
class PersonaLibraryUnreadableException : IllegalStateException(
    "Your saved persona library couldn't be read, so this change wasn't saved. " +
        "Your existing personas have been left untouched."
)

private val Context.personaDataStore: DataStore<Preferences> by preferencesDataStore(name = "personas")

/**
 * The prompt / persona library (feature B6): saved personas (system prompts)
 * and quick commands (composer snippets), plus the per-chat persona bindings.
 *
 * Storage is this repository's own DataStore file ("personas") holding two
 * JSON values — the library list and a sessionId → personaId bindings map.
 * A dedicated store (rather than the Room database) keeps the feature free of
 * any schema change, and keeping the bindings here (rather than a column on
 * the session row) means deleting a persona can strip its bindings in the
 * same atomic edit, so a dangling reference is impossible by construction.
 *
 * Seeding: while no library value has ever been written, reads report
 * [PersonaStarters]. The first mutation persists the full list including the
 * starters, so from then on the stored list is authoritative and deleted
 * starters stay deleted.
 */
@Singleton
class PersonaRepository internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(context.personaDataStore)

    private val json = Json { ignoreUnknownKeys = true }
    private val exportJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val personasKey = stringPreferencesKey("personas_json")
    private val bindingsKey = stringPreferencesKey("session_persona_bindings_json")

    private val _storageError = MutableStateFlow(false)

    /**
     * True while a stored library value exists but cannot be decoded.
     * Reads degrade to an empty library (chat must not crash), but the
     * state is surfaced here — and mutations refuse (see
     * [PersonaLibraryUnreadableException]) — instead of the corruption
     * being silent until a mutation makes the loss permanent.
     */
    val storageError: StateFlow<Boolean> = _storageError.asStateFlow()

    /** The full library (personas and quick commands), in insertion order. */
    val personas: Flow<List<Persona>> = dataStore.data.map { preferences -> decodePersonas(preferences) }

    /** sessionId → personaId for every chat with a persona bound. */
    val sessionBindings: Flow<Map<String, String>> = dataStore.data.map { preferences -> decodeBindings(preferences) }

    /** Null when [raw] cannot be decoded as a persona list. */
    private fun decodeStoredPersonas(raw: String): List<Persona>? = try {
        json.decodeFromString<List<Persona>>(raw)
    } catch (_: Exception) {
        null
    }

    private fun decodePersonas(preferences: Preferences): List<Persona> {
        val raw = preferences[personasKey] ?: run {
            _storageError.value = false
            return PersonaStarters.starters()
        }
        val decoded = decodeStoredPersonas(raw)
        _storageError.value = decoded == null
        // A corrupt stored value must not crash chat: reads report an
        // empty library (and storageError says why). The value itself is
        // left untouched — only an explicit wipe may replace it.
        return decoded ?: emptyList()
    }

    /**
     * The library as a mutation's starting point. Unlike the read path,
     * a present-but-undecodable value is an error here: building the
     * mutation on an empty list and writing it back would permanently
     * discard a library that was only unreadable, not gone.
     */
    private fun decodePersonasForWrite(preferences: Preferences): List<Persona> {
        val raw = preferences[personasKey] ?: return PersonaStarters.starters()
        return decodeStoredPersonas(raw) ?: throw PersonaLibraryUnreadableException()
    }

    private fun decodeBindings(preferences: Preferences): Map<String, String> {
        val raw = preferences[bindingsKey] ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun MutablePreferences.writePersonas(list: List<Persona>) {
        this[personasKey] = json.encodeToString(list)
    }

    private fun MutablePreferences.writeBindings(bindings: Map<String, String>) {
        this[bindingsKey] = json.encodeToString(bindings)
    }

    /** Creates an entry; returns the stored persona (with its generated id). */
    suspend fun createPersona(name: String, prompt: String, kind: PersonaKind): Persona {
        val now = System.currentTimeMillis()
        val persona = Persona(
            id = UUID.randomUUID().toString(),
            name = PersonaValidation.normalizedName(name),
            prompt = PersonaValidation.normalizedPrompt(prompt),
            kind = kind,
            isBuiltIn = false,
            createdAt = now,
            updatedAt = now
        )
        dataStore.edit { preferences ->
            preferences.writePersonas(decodePersonasForWrite(preferences) + persona)
        }
        return persona
    }

    /** Replaces name/prompt/kind of an existing entry; unknown ids are ignored. */
    suspend fun updatePersona(id: String, name: String, prompt: String, kind: PersonaKind) {
        val cleanName = PersonaValidation.normalizedName(name)
        val cleanPrompt = PersonaValidation.normalizedPrompt(prompt)
        val now = System.currentTimeMillis()
        dataStore.edit { preferences ->
            val current = decodePersonasForWrite(preferences)
            if (current.none { it.id == id }) return@edit
            preferences.writePersonas(
                current.map { entry ->
                    if (entry.id == id) {
                        entry.copy(name = cleanName, prompt = cleanPrompt, kind = kind, updatedAt = now)
                    } else {
                        entry
                    }
                }
            )
            // An entry edited away from PERSONA kind is no longer a
            // persona at all: strip chat bindings pointing at it, the
            // same cleanup deletePersona performs. Without this the
            // stale bindings keep the entry in PersonasScreen's
            // "in use" counts and bind logic forever, since bindings
            // are otherwise only validated at bind time.
            if (kind != PersonaKind.PERSONA) {
                val bindings = decodeBindings(preferences)
                if (bindings.values.any { it == id }) {
                    preferences.writeBindings(bindings.filterValues { it != id })
                }
            }
        }
    }

    /** Adds a copy of an entry ("<name> copy", fresh id, never built-in); returns the copy or null. */
    suspend fun duplicatePersona(id: String): Persona? {
        var copy: Persona? = null
        dataStore.edit { preferences ->
            val current = decodePersonasForWrite(preferences)
            val source = current.firstOrNull { it.id == id } ?: return@edit
            val now = System.currentTimeMillis()
            val duplicate = source.copy(
                id = UUID.randomUUID().toString(),
                name = (source.name + " copy").take(PersonaValidation.MAX_NAME_CHARS),
                isBuiltIn = false,
                createdAt = now,
                updatedAt = now
            )
            copy = duplicate
            preferences.writePersonas(current + duplicate)
        }
        return copy
    }

    /**
     * Deletes an entry AND every chat binding pointing at it, in one edit —
     * the checklist's graceful-degradation rule: chats that used the deleted
     * persona silently fall back to the default system prompt.
     */
    suspend fun deletePersona(id: String) {
        dataStore.edit { preferences ->
            val current = decodePersonasForWrite(preferences)
            if (current.none { it.id == id }) return@edit
            preferences.writePersonas(current.filterNot { it.id == id })
            val bindings = decodeBindings(preferences)
            if (bindings.values.any { it == id }) {
                preferences.writeBindings(bindings.filterValues { it != id })
            }
        }
    }

    /**
     * Binds a persona to a chat (or clears the binding with null). A persona
     * id that does not resolve to an existing PERSONA entry is never
     * persisted — the binding is cleared instead — so stored bindings always
     * point at a live entry.
     */
    suspend fun setSessionPersona(sessionId: String, personaId: String?) {
        dataStore.edit { preferences ->
            val bindings = decodeBindings(preferences).toMutableMap()
            val target = personaId?.takeIf { wanted ->
                decodePersonas(preferences).any { it.id == wanted && it.kind == PersonaKind.PERSONA }
            }
            if (target == null) {
                bindings.remove(sessionId)
            } else {
                bindings[sessionId] = target
            }
            preferences.writeBindings(bindings)
        }
    }

    /**
     * The persona bound to a chat, or null when the chat follows the default
     * prompt. Also null when the binding's persona no longer exists (deleted
     * before bindings were stripped, or an older corrupt store) — the chat
     * degrades to default instead of failing.
     */
    suspend fun personaForSession(sessionId: String): Persona? {
        val preferences = dataStore.data.first()
        val personaId = decodeBindings(preferences)[sessionId] ?: return null
        return decodePersonas(preferences)
            .firstOrNull { it.id == personaId && it.kind == PersonaKind.PERSONA }
    }

    /** Serializes the whole library for sharing / backup. */
    suspend fun exportJson(): String {
        val current = personas.first()
        return exportJson.encodeToString(PersonaLibraryExport(personas = current))
    }

    /**
     * Imports entries from an export payload, merging per [mergeImportedPersonas].
     * Returns how many entries were added. Throws on malformed JSON or a
     * payload that is not a persona library export.
     */
    suspend fun importJson(text: String): Int {
        val payload = exportJson.decodeFromString<PersonaLibraryExport>(text.trim())
        require(payload.format == PersonaLibraryExport.FORMAT) {
            "Not an OpenDroid persona library export"
        }
        var added = 0
        dataStore.edit { preferences ->
            val current = decodePersonasForWrite(preferences)
            val merged = mergeImportedPersonas(current, payload.personas)
            added = merged.size - current.size
            preferences.writePersonas(merged)
        }
        return added
    }

    /**
     * Erases the library and all bindings (the A6 forget-everything wipe).
     * The DataStore file itself remains but empty — including no seed marker,
     * so the next read reports the starters again, exactly like a fresh
     * install's first run.
     */
    suspend fun clearAllPersonas() {
        dataStore.edit { preferences -> preferences.clear() }
    }
}
