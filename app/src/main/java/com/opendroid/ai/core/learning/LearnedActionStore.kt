package com.opendroid.ai.core.learning

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.actionLearningDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "action_learning")

/**
 * Persistent store for B5 learned action mappings (see [LearnedActionMapping]).
 *
 * Backed by its own Preferences DataStore file ("action_learning"), separate
 * from the settings store: mappings are user teaching data, not settings, and
 * must survive a settings reset that is not a full wipe.
 *
 * Two read paths exist on purpose:
 *  - [mappings] / [hasMapping] read an in-memory cache so the dispatcher's
 *    synchronous `hasAction` check stays cheap; the cache is fed by a
 *    collector started at construction and may briefly lag a write made in
 *    the same instant.
 *  - [mappingFor] reads DataStore directly, so the dispatcher's suspend
 *    resolution path always sees the latest persisted state.
 */
@Singleton
class LearnedActionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore: DataStore<Preferences> = context.actionLearningDataStore

    private val mappingsKey = stringPreferencesKey("learned_action_mappings_json")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableMappings = MutableStateFlow<Map<String, LearnedActionMapping>>(emptyMap())
    val mappings: StateFlow<Map<String, LearnedActionMapping>> = mutableMappings.asStateFlow()

    val mappingsFlow: Flow<Map<String, LearnedActionMapping>> = dataStore.data
        .map { preferences -> LearnedActionCodec.decode(preferences[mappingsKey]) }

    init {
        scope.launch {
            mappingsFlow.collect { decoded -> mutableMappings.value = decoded }
        }
    }

    /** Synchronous cache lookup for the dispatcher's `hasAction` fast path. */
    fun hasMapping(normalizedAction: String): Boolean =
        mutableMappings.value.containsKey(normalizedAction)

    /** Authoritative suspend lookup used on the dispatch path. */
    suspend fun mappingFor(normalizedAction: String): LearnedActionMapping? =
        mappingsFlow.first()[normalizedAction]

    suspend fun save(mapping: LearnedActionMapping) {
        val key = normalizeLearnedActionKey(mapping.attemptedAction)
        val stamped = mapping.copy(
            attemptedAction = key,
            updatedAt = System.currentTimeMillis()
        )
        dataStore.edit { preferences ->
            val current = LearnedActionCodec.decode(preferences[mappingsKey]).toMutableMap()
            current[key] = stamped
            preferences[mappingsKey] = LearnedActionCodec.encode(current)
        }
    }

    /** Erases every mapping (used by the A6 forget-everything wipe). */
    suspend fun clearAll() {
        dataStore.edit { preferences -> preferences.remove(mappingsKey) }
    }

    /** Removes a mapping (the group's review state falls back to PENDING). */
    suspend fun remove(attemptedAction: String) {
        val key = normalizeLearnedActionKey(attemptedAction)
        dataStore.edit { preferences ->
            val current = LearnedActionCodec.decode(preferences[mappingsKey]).toMutableMap()
            if (current.remove(key) != null) {
                preferences[mappingsKey] = LearnedActionCodec.encode(current)
            }
        }
    }
}
