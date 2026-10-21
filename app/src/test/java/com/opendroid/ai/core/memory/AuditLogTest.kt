package com.opendroid.ai.core.memory

import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AuditLogTest {

    private fun entry(
        success: Boolean,
        errorMessage: String? = null,
        timestamp: Long = 0L,
        actionType: String = "OPEN_APP",
        description: String = "Open Settings"
    ) = TaskHistoryEntity(
        stepId = "s1",
        planId = "p1",
        description = description,
        actionType = actionType,
        paramsJson = "{}",
        success = success,
        resultData = null,
        errorMessage = errorMessage,
        timestamp = timestamp
    )

    @Test
    fun `successful entry classifies as SUCCESS`() {
        assertEquals(AuditOutcome.SUCCESS, AuditLog.outcomeOf(entry(success = true)))
    }

    @Test
    fun `failed entry with the denial marker classifies as DENIED`() {
        val denied = entry(success = false, errorMessage = AuditLog.DENIED_MESSAGE)
        assertEquals(AuditOutcome.DENIED, AuditLog.outcomeOf(denied))
        assertTrue(AuditLog.isDenied(denied))
    }

    @Test
    fun `denial prefix alone still classifies as DENIED`() {
        val denied = entry(success = false, errorMessage = "Denied by user")
        assertEquals(AuditOutcome.DENIED, AuditLog.outcomeOf(denied))
    }

    @Test
    fun `failed entry with an ordinary error classifies as FAILED`() {
        val failed = entry(success = false, errorMessage = "App not found")
        assertEquals(AuditOutcome.FAILED, AuditLog.outcomeOf(failed))
        assertFalse(AuditLog.isDenied(failed))
    }

    @Test
    fun `failed entry with no error message classifies as FAILED`() {
        assertEquals(AuditOutcome.FAILED, AuditLog.outcomeOf(entry(success = false, errorMessage = null)))
    }

    @Test
    fun `success wins even if an error message is present`() {
        val odd = entry(success = true, errorMessage = AuditLog.DENIED_MESSAGE)
        assertEquals(AuditOutcome.SUCCESS, AuditLog.outcomeOf(odd))
    }

    private fun step(id: String, action: String, status: StepStatus) = PlanStep(
        stepId = id,
        order = id.removePrefix("s").toIntOrNull() ?: 0,
        description = "step $id",
        action = action,
        params = mapOf("k" to "v"),
        status = status
    )

    private fun plan(steps: List<PlanStep>) = Plan(
        planId = "plan-42",
        goal = "Do the thing",
        estimatedDuration = "1m",
        estimatedSteps = steps.size,
        steps = steps
    )

    @Test
    fun `denied records cover only steps that never ran`() {
        val plan = plan(
            listOf(
                step("s1", "OPEN_APP", StepStatus.COMPLETED),
                step("s2", "SEND_SMS", StepStatus.PENDING),
                step("s3", "MAKE_CALL", StepStatus.PENDING)
            )
        )
        val records = AuditLog.deniedStepRecords(plan)
        assertEquals(listOf("s2", "s3"), records.map { it.stepId })
        assertEquals(listOf("SEND_SMS", "MAKE_CALL"), records.map { it.actionType })
        assertTrue(records.all { it.planId == "plan-42" })
        assertEquals(mapOf("k" to "v"), records.first().params)
    }

    @Test
    fun `denied plan with no pending steps falls back to a single plan-level record`() {
        val plan = plan(listOf(step("s1", "OPEN_APP", StepStatus.COMPLETED)))
        val records = AuditLog.deniedStepRecords(plan)
        assertEquals(1, records.size)
        assertEquals(AuditLog.PLAN_DENIED_ACTION_TYPE, records.single().actionType)
        assertEquals("Do the thing", records.single().description)
        assertEquals("plan-42", records.single().planId)
    }

    @Test
    fun `denied plan with no steps at all still produces the fallback record`() {
        val records = AuditLog.deniedStepRecords(plan(emptyList()))
        assertEquals(1, records.size)
        assertEquals(AuditLog.PLAN_DENIED_ACTION_TYPE, records.single().actionType)
    }

    @Test
    fun `day labels and grouping use Today and Yesterday relative to now`() {
        val zone = ZoneId.of("UTC")
        // 2026-10-21T12:00:00Z as "now"; entries at 09:00 same day, 2026-10-20T09:00, 2026-10-10T09:00.
        val now = 1792584000000L // 2026-10-21T12:00:00Z
        val today = entry(success = true, timestamp = 1792573200000L) // 2026-10-21T09:00:00Z
        val yesterday = entry(success = true, timestamp = 1792486800000L) // 2026-10-20T09:00:00Z
        val older = entry(success = false, timestamp = 1791622800000L) // 2026-10-10T09:00:00Z

        assertEquals("Today", AuditLog.dayLabel(today.timestamp, now, zone))
        assertEquals("Yesterday", AuditLog.dayLabel(yesterday.timestamp, now, zone))
        assertTrue(AuditLog.dayLabel(older.timestamp, now, zone).contains("2026"))

        val grouped = AuditLog.groupByDay(listOf(today, yesterday, older), now, zone)
        assertEquals(listOf("Today", "Yesterday"), grouped.map { it.first }.take(2))
        assertEquals(listOf(today), grouped[0].second)
        assertEquals(listOf(yesterday), grouped[1].second)
        assertEquals(listOf(older), grouped[2].second)
    }

    @Test
    fun `groupByDay keeps same-day entries together in input order`() {
        val zone = ZoneId.of("UTC")
        val now = 1792584000000L // 2026-10-21T12:00:00Z
        val a = entry(success = true, timestamp = 1792573200000L, description = "a")
        val b = entry(success = false, timestamp = 1792569600000L, description = "b")
        val grouped = AuditLog.groupByDay(listOf(a, b), now, zone)
        assertEquals(1, grouped.size)
        assertEquals(listOf("a", "b"), grouped.single().second.map { it.description })
    }
}
