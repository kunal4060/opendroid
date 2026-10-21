package com.opendroid.ai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opendroid.ai.data.db.entities.ResearchRunEntity
import com.opendroid.ai.data.db.entities.ResearchSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ResearchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<ResearchSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: ResearchRunEntity)

    /** Sources of one message (or one run, on the action path), in citation order. */
    @Query("SELECT * FROM research_sources WHERE messageId = :messageId ORDER BY position ASC")
    fun getSourcesForMessage(messageId: String): Flow<List<ResearchSourceEntity>>

    /** Every stored source, newest run first — the chat layer groups by messageId. */
    @Query("SELECT * FROM research_sources ORDER BY fetchedAt DESC, position ASC")
    fun getAllSources(): Flow<List<ResearchSourceEntity>>

    @Query("SELECT * FROM research_runs WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getRunsForSession(sessionId: String): Flow<List<ResearchRunEntity>>

    @Query("SELECT * FROM research_runs ORDER BY createdAt ASC")
    fun getAllRuns(): Flow<List<ResearchRunEntity>>

    @Query("DELETE FROM research_sources WHERE messageId = :messageId")
    suspend fun deleteSourcesForMessage(messageId: String)
}
