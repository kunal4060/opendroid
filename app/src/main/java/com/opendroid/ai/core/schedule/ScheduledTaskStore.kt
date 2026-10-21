package com.opendroid.ai.core.schedule

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.scheduledTasksDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "scheduled_tasks")

/** The whole persisted document: every schedule, plus sync-dismissal bookkeeping. */
@Serializable
private data class ScheduleState(
    val tasks: List<ScheduledTask> = emptyList(),
    /** sourceKey -> cron string at dismissal time, for sync-materialised tasks the user deleted. */
    val dismissedSourceCrons: Map<String, String> = emptyMap()
)

/**
 * Persistence for C4 schedules: one JSON document in a dedicated
 * Preferences DataStore (no Room schema change, per the C4 constraint).
 *
 * All read-modify-write operations are serialised through one Mutex, which
 * is also what makes [tryClaim] a sound compare-and-set: the alarm lane and
 * the WorkManager sweep lane both funnel claims through this process-wide
 * singleton, so exactly one of them wins a given occurrence.
 */
@Singleton
class ScheduledTaskStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore: DataStore<Preferences> = context.scheduledTasksDataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    val tasks: Flow<List<ScheduledTask>> = dataStore.data.map { prefs -> decode(prefs).tasks }

    suspend fun snapshot(): List<ScheduledTask> = decode(dataStore.data.first()).tasks

    suspend fun get(id: String): ScheduledTask? = snapshot().firstOrNull { it.id == id }

    suspend fun upsert(task: ScheduledTask) = mutex.withLock {
        mutate { state ->
            val existing = state.tasks.indexOfFirst { it.id == task.id }
            val newTasks = if (existing >= 0) {
                state.tasks.toMutableList().also { it[existing] = task }
            } else {
                state.tasks + task
            }
            state.copy(tasks = newTasks)
        }
    }

    /** Applies [transform] to the task with [id]; returns the updated task, or null if absent. */
    suspend fun update(id: String, transform: (ScheduledTask) -> ScheduledTask): ScheduledTask? =
        mutex.withLock {
            var updated: ScheduledTask? = null
            mutate { state ->
                state.copy(tasks = state.tasks.map { task ->
                    if (task.id == id) transform(task).also { updated = it } else task
                })
            }
            updated
        }

    suspend fun delete(id: String): ScheduledTask? = mutex.withLock {
        var removed: ScheduledTask? = null
        mutate { state ->
            removed = state.tasks.firstOrNull { it.id == id }
            state.copy(tasks = state.tasks.filterNot { it.id == id })
        }
        removed
    }

    /**
     * Atomically claims a task for a run: succeeds only when the task is
     * enabled and in a claimable state (ARMED, AWAITING_APPROVAL — a new
     * occurrence supersedes a still-unapproved earlier one — FAILED, or a
     * RUNNING claim that has gone stale, i.e. the process died mid-run).
     * On success the task is RUNNING with [nowMillis] as its claim time.
     * Returns the claimed task, or null when another lane got there first.
     */
    suspend fun tryClaim(id: String, nowMillis: Long): ScheduledTask? = mutex.withLock {
        var claimed: ScheduledTask? = null
        mutate { state ->
            state.copy(tasks = state.tasks.map { task ->
                if (task.id != id) return@map task
                val claimable = task.enabled && when (task.status) {
                    ScheduledTaskStatus.ARMED,
                    ScheduledTaskStatus.AWAITING_APPROVAL,
                    ScheduledTaskStatus.FAILED -> true
                    ScheduledTaskStatus.RUNNING ->
                        nowMillis - (task.claimedAtMillis ?: 0L) > STUCK_RUN_MILLIS
                    ScheduledTaskStatus.DONE,
                    ScheduledTaskStatus.MISSED -> false
                }
                if (claimable) {
                    task.copy(
                        status = ScheduledTaskStatus.RUNNING,
                        claimedAtMillis = nowMillis
                    ).also { claimed = it }
                } else {
                    task
                }
            })
        }
        claimed
    }

    /** Records that the user deleted the sync-materialised task for [sourceKey]. */
    suspend fun dismissSource(sourceKey: String, cronExpression: String?) = mutex.withLock {
        mutate { state ->
            state.copy(
                dismissedSourceCrons = state.dismissedSourceCrons +
                    (sourceKey to (cronExpression ?: ""))
            )
        }
    }

    suspend fun dismissedSourceCrons(): Map<String, String> =
        decode(dataStore.data.first()).dismissedSourceCrons

    private suspend fun mutate(transform: (ScheduleState) -> ScheduleState) {
        dataStore.edit { prefs ->
            prefs[KEY_STATE] = json.encodeToString(transform(decode(prefs)))
        }
    }

    private fun decode(prefs: Preferences): ScheduleState {
        val raw = prefs[KEY_STATE] ?: return ScheduleState()
        return try {
            json.decodeFromString<ScheduleState>(raw)
        } catch (e: Exception) {
            // A corrupt state file must not take scheduling down with it;
            // worst case the user re-creates their schedules.
            ScheduleState()
        }
    }

    companion object {
        private val KEY_STATE = stringPreferencesKey("scheduled_tasks_state_json")

        /** A RUNNING claim older than this is treated as a crashed run and reclaimable. */
        const val STUCK_RUN_MILLIS: Long = 30L * 60L * 1000L
    }
}
