package com.opendroid.ai.data.db.dao

import androidx.room.*
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskHistoryDao {
    @Query("SELECT * FROM task_history ORDER BY timestamp DESC")
    fun getTaskHistoryFlow(): Flow<List<TaskHistoryEntity>>

    @Query("SELECT * FROM task_history WHERE planId = :planId ORDER BY timestamp ASC, id ASC")
    suspend fun getTaskHistoryForPlan(planId: String): List<TaskHistoryEntity>

    /**
     * Audit-log viewer (B4) filtered stream. [outcome] is an
     * [com.opendroid.ai.core.memory.AuditOutcome] name or "ALL". A row counts
     * as DENIED when it failed with an error message starting with the denial
     * marker (AuditLog.DENIED_PREFIX — the literal below must stay in sync);
     * every other failed row is FAILED. [searchPattern] is a pre-wrapped
     * LIKE pattern (e.g. "%call%") or null for no text search.
     */
    @Query(
        """
        SELECT * FROM task_history
        WHERE (:actionType IS NULL OR actionType = :actionType)
          AND (:searchPattern IS NULL OR description LIKE :searchPattern OR actionType LIKE :searchPattern)
          AND (
            :outcome = 'ALL'
            OR (:outcome = 'SUCCESS' AND success = 1)
            OR (:outcome = 'FAILED' AND success = 0 AND (errorMessage IS NULL OR errorMessage NOT LIKE 'Denied by user%'))
            OR (:outcome = 'DENIED' AND success = 0 AND errorMessage LIKE 'Denied by user%')
          )
        ORDER BY timestamp DESC
        """
    )
    fun getFilteredHistoryFlow(
        outcome: String,
        actionType: String?,
        searchPattern: String?
    ): Flow<List<TaskHistoryEntity>>

    @Query("SELECT DISTINCT actionType FROM task_history ORDER BY actionType ASC")
    fun getDistinctActionTypesFlow(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM task_history")
    fun getTotalCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM task_history WHERE success = 1")
    fun getSuccessCountFlow(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM task_history WHERE success = 0 " +
            "AND (errorMessage IS NULL OR errorMessage NOT LIKE 'Denied by user%')"
    )
    fun getFailureCountFlow(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM task_history WHERE success = 0 " +
            "AND errorMessage LIKE 'Denied by user%'"
    )
    fun getDeniedCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(task: TaskHistoryEntity)

    @Query("DELETE FROM task_history")
    suspend fun clearAll()
}
