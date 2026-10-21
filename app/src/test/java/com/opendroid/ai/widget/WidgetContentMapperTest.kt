package com.opendroid.ai.widget

import com.opendroid.ai.core.llm.RoutingStatus
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.models.HabitRoutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The widget is only honest if the distillation from live data is right:
 * which routine is offered, how the last task reads, and how routing is
 * labelled all come from [WidgetContentMapper].
 */
class WidgetContentMapperTest {

    private fun routine(
        id: String,
        triggerCron: String = "",
        triggerLabel: String = "",
        lastDetectedAt: Long = 0L
    ) = HabitRoutine(
        id = id,
        name = "Routine $id",
        description = "",
        triggerLabel = triggerLabel,
        triggerCron = triggerCron,
        lastDetectedAt = lastDetectedAt
    )

    private fun task(description: String, success: Boolean) = TaskHistoryEntity(
        stepId = "step-1",
        planId = "plan-1",
        description = description,
        actionType = "TEST",
        paramsJson = "{}",
        success = success,
        resultData = null,
        errorMessage = null
    )

    @Test
    fun `no routines means no next routine`() {
        assertNull(WidgetContentMapper.pickNextRoutine(emptyList()))
    }

    @Test
    fun `a scheduled routine beats a more recently detected unscheduled one`() {
        val unscheduled = routine("a", lastDetectedAt = 900L)
        val scheduled = routine("b", triggerCron = "0 7 * * *", triggerLabel = "7:00 AM", lastDetectedAt = 100L)

        assertEquals("b", WidgetContentMapper.pickNextRoutine(listOf(unscheduled, scheduled))?.id)
    }

    @Test
    fun `among scheduled routines the most recently detected wins`() {
        val older = routine("a", triggerCron = "0 7 * * *", lastDetectedAt = 100L)
        val newer = routine("b", triggerCron = "0 8 * * *", lastDetectedAt = 500L)

        assertEquals("b", WidgetContentMapper.pickNextRoutine(listOf(older, newer))?.id)
    }

    @Test
    fun `last task text reflects outcome and description`() {
        assertEquals("Done: Turn on Wi-Fi", WidgetContentMapper.lastTaskText(task("Turn on Wi-Fi", true)))
        assertEquals("Failed: Send SMS", WidgetContentMapper.lastTaskText(task("Send SMS", false)))
        assertNull(WidgetContentMapper.lastTaskText(null))
        assertNull(WidgetContentMapper.lastTaskText(task("   ", true)))
    }

    @Test
    fun `routing labels come from the live status`() {
        assertEquals("Cloud · Gemini", WidgetContentMapper.routingLabel(RoutingStatus.Cloud("Gemini")))
        assertEquals("On-device · LiteRT", WidgetContentMapper.routingLabel(RoutingStatus.OnDevice("LiteRT")))
        assertEquals(
            "Offline · on-device ready",
            WidgetContentMapper.routingLabel(RoutingStatus.OfflineWillUseOnDevice("LiteRT"))
        )
        assertEquals("Offline · no route", WidgetContentMapper.routingLabel(RoutingStatus.OfflineNoRoute))
    }

    @Test
    fun `content combines every source and keeps the routine trigger label`() {
        val content = WidgetContentMapper.content(
            activeRoutines = listOf(routine("r1", triggerLabel = "Weekdays 7:00 AM", lastDetectedAt = 5L)),
            latestTask = task("Open Maps", true),
            lastBriefingAt = 1234L,
            routingStatus = RoutingStatus.OnDevice("LiteRT")
        )

        assertEquals("r1", content.nextRoutineId)
        assertEquals("Routine r1", content.nextRoutineName)
        assertEquals("Weekdays 7:00 AM", content.nextRoutineTrigger)
        assertEquals("Done: Open Maps", content.lastTaskText)
        assertEquals(1234L, content.lastBriefingAt)
        assertEquals("On-device · LiteRT", content.routingLabel)
    }
}
