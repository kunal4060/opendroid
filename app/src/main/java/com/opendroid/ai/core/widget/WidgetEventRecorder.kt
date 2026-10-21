package com.opendroid.ai.core.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Small event ledger for things the home-screen widget displays but no Room
 * table records — today, exactly one: when the last morning briefing was
 * generated.
 *
 * Briefings are produced by [com.opendroid.ai.core.routine.HabitRoutineEngine]
 * and returned as a string to whoever asked; nothing persists them. Rather
 * than change the engine's contract (or its constructor, which other code
 * and tests build directly), the engine stamps this ledger from inside
 * `generateMorningBriefing`, and the widget state sync reads it back. The
 * store lives outside Room on purpose: C1 adds no schema change.
 */
object WidgetEventRecorder {

    private val LAST_BRIEFING_AT = longPreferencesKey("last_briefing_at")

    private val Context.widgetMetaStore by preferencesDataStore(name = "opendroid_widget_meta")

    /** Stamps "a briefing was generated just now". Best-effort by design. */
    suspend fun recordBriefingGenerated(context: Context) {
        context.widgetMetaStore.edit { prefs ->
            prefs[LAST_BRIEFING_AT] = System.currentTimeMillis()
        }
    }

    /** When the last briefing was generated, or null if none ever was. */
    fun lastBriefingAt(context: Context): Flow<Long?> =
        context.widgetMetaStore.data.map { prefs -> prefs[LAST_BRIEFING_AT] }

    /** Erases the ledger. Used by the A6 wipe. */
    suspend fun clearAll(context: Context) {
        context.widgetMetaStore.edit { prefs -> prefs.clear() }
    }
}
