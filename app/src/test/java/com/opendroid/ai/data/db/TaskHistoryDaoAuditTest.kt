package com.opendroid.ai.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.memory.AuditLog
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B4 audit-log DAO tests: the filtered query must keep successes, failures
 * and user denials apart — above all, a denied row (success = false with the
 * denial marker as its error message, exactly the shape AgentLoop's denial
 * path writes) must surface under DENIED and never be counted as a plain
 * failure, and plain failures must never leak into DENIED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class TaskHistoryDaoAuditTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var dao: TaskHistoryDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.taskHistoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun row(
        stepId: String,
        actionType: String,
        success: Boolean,
        errorMessage: String? = null,
        description: String = "step $stepId",
        planId: String = "plan-1",
        timestamp: Long
    ) = TaskHistoryEntity(
        stepId = stepId,
        planId = planId,
        description = description,
        actionType = actionType,
        paramsJson = "{}",
        success = success,
        resultData = null,
        errorMessage = errorMessage,
        timestamp = timestamp
    )

    private suspend fun seed() {
        dao.insertHistory(row("s1", "OPEN_APP", success = true, timestamp = 1000))
        dao.insertHistory(
            row("s2", "SEND_SMS", success = false, errorMessage = "No SIM available", timestamp = 2000)
        )
        dao.insertHistory(
            row("s3", "SEND_SMS", success = false, errorMessage = AuditLog.DENIED_MESSAGE, timestamp = 3000)
        )
        dao.insertHistory(
            row("s4", "MAKE_CALL", success = false, errorMessage = AuditLog.DENIED_MESSAGE, timestamp = 4000)
        )
        dao.insertHistory(
            row(
                "plan-gen", "PLAN_GENERATION", success = false,
                errorMessage = "LLM timeout", planId = "n/a", timestamp = 5000
            )
        )
    }

    @Test
    fun `ALL outcome returns every row newest first`() = runBlocking {
        seed()
        val all = dao.getFilteredHistoryFlow("ALL", null, null).first()
        assertEquals(5, all.size)
        assertEquals(listOf(5000L, 4000L, 3000L, 2000L, 1000L), all.map { it.timestamp })
    }

    @Test
    fun `SUCCESS outcome returns only successful rows`() = runBlocking {
        seed()
        val rows = dao.getFilteredHistoryFlow("SUCCESS", null, null).first()
        assertEquals(listOf("s1"), rows.map { it.stepId })
    }

    @Test
    fun `FAILED outcome excludes denied rows`() = runBlocking {
        seed()
        val rows = dao.getFilteredHistoryFlow("FAILED", null, null).first()
        assertEquals(setOf("s2", "plan-gen"), rows.map { it.stepId }.toSet())
    }

    @Test
    fun `DENIED outcome returns only rows carrying the denial marker`() = runBlocking {
        seed()
        val rows = dao.getFilteredHistoryFlow("DENIED", null, null).first()
        assertEquals(setOf("s3", "s4"), rows.map { it.stepId }.toSet())
        assertTrue(rows.all { !it.success && it.errorMessage!!.startsWith(AuditLog.DENIED_PREFIX) })
    }

    @Test
    fun `action type filter narrows within outcome`() = runBlocking {
        seed()
        val deniedSms = dao.getFilteredHistoryFlow("DENIED", "SEND_SMS", null).first()
        assertEquals(listOf("s3"), deniedSms.map { it.stepId })
        val allSms = dao.getFilteredHistoryFlow("ALL", "SEND_SMS", null).first()
        assertEquals(setOf("s2", "s3"), allSms.map { it.stepId }.toSet())
    }

    @Test
    fun `search pattern matches description and action type`() = runBlocking {
        seed()
        val byDescription = dao.getFilteredHistoryFlow("ALL", null, "%step s4%").first()
        assertEquals(listOf("s4"), byDescription.map { it.stepId })
        val byAction = dao.getFilteredHistoryFlow("ALL", null, "%MAKE_CALL%").first()
        assertEquals(listOf("s4"), byAction.map { it.stepId })
        val noMatch = dao.getFilteredHistoryFlow("ALL", null, "%nonexistent%").first()
        assertTrue(noMatch.isEmpty())
    }

    @Test
    fun `distinct action types are returned sorted`() = runBlocking {
        seed()
        val types = dao.getDistinctActionTypesFlow().first()
        assertEquals(listOf("MAKE_CALL", "OPEN_APP", "PLAN_GENERATION", "SEND_SMS"), types)
    }

    @Test
    fun `counts split total success failure and denied`() = runBlocking {
        seed()
        assertEquals(5, dao.getTotalCountFlow().first())
        assertEquals(1, dao.getSuccessCountFlow().first())
        assertEquals(2, dao.getFailureCountFlow().first())
        assertEquals(2, dao.getDeniedCountFlow().first())
    }

    @Test
    fun `a freshly written denial row appears under DENIED immediately`() = runBlocking {
        // The exact row shape AgentLoop.logPlanDenial writes via
        // MemoryManager -> MemoryRepository for a rejected plan step.
        dao.insertHistory(
            row(
                "step-9", "DELETE_FILE", success = false,
                errorMessage = AuditLog.DENIED_MESSAGE,
                description = "Delete the file",
                timestamp = 9000
            )
        )
        val denied = dao.getFilteredHistoryFlow("DENIED", null, null).first()
        assertEquals(listOf("step-9"), denied.map { it.stepId })
        assertEquals(1, dao.getDeniedCountFlow().first())
        assertEquals(0, dao.getFailureCountFlow().first())
    }
}
