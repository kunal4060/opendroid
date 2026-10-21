package com.opendroid.ai.core.learning

import com.opendroid.ai.data.db.entities.UnknownActionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnknownActionGroupingTest {

    private fun row(
        action: String,
        goal: String = "",
        timestamp: Long,
        fixStatus: String = "FAILED",
        fixedWith: String? = null
    ) = UnknownActionEntity(
        attemptedAction = action,
        goal = goal,
        timestamp = timestamp,
        fixStatus = fixStatus,
        wasAutoFixed = fixStatus == "AUTO_FIXED",
        fixedWith = fixedWith
    )

    @Test
    fun `verb variants collapse into one group`() {
        val groups = UnknownActionGrouping.group(
            listOf(
                row("SEND-WHATSAPP", timestamp = 100),
                row("send whatsapp", timestamp = 300),
                row("SEND_WHATSAPP", timestamp = 200)
            )
        )
        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals("SEND_WHATSAPP", group.key)
        assertEquals(3, group.occurrences)
        assertEquals(300L, group.lastSeen)
        assertEquals("send whatsapp", group.displayAction)
    }

    @Test
    fun `sample goal comes from the newest row that recorded one`() {
        val groups = UnknownActionGrouping.group(
            listOf(
                row("FLY_DRONE", goal = "take an aerial photo", timestamp = 100),
                row("FLY_DRONE", goal = "", timestamp = 200)
            )
        )
        assertEquals("take an aerial photo", groups.single().sampleGoal)
    }

    @Test
    fun `fix status counts and suggested target are derived from rows`() {
        val groups = UnknownActionGrouping.group(
            listOf(
                row("WHATSAPP_SEND", timestamp = 100, fixStatus = "FAILED"),
                row(
                    "WHATSAPP_SEND", timestamp = 200,
                    fixStatus = "AUTO_FIXED", fixedWith = "SEND_WHATSAPP"
                ),
                row(
                    "WHATSAPP_SEND", timestamp = 300,
                    fixStatus = "AUTO_FIXED", fixedWith = "SKIP"
                )
            )
        )
        val group = groups.single()
        assertEquals(mapOf("FAILED" to 1, "AUTO_FIXED" to 2), group.fixStatusCounts)
        // Newest usable suggestion wins; "SKIP" describes an outcome, not a target.
        assertEquals("SEND_WHATSAPP", group.suggestedTarget)
    }

    @Test
    fun `no suggestion when nothing was ever auto fixed to a real action`() {
        val groups = UnknownActionGrouping.group(
            listOf(row("DO_MAGIC", timestamp = 1, fixStatus = "FAILED"))
        )
        assertNull(groups.single().suggestedTarget)
    }

    @Test
    fun `groups sort by most recent activity and recent list is capped`() {
        val rows = (1..7).map { row("OLD_VERB", timestamp = it.toLong()) } +
            listOf(row("NEW_VERB", timestamp = 1_000))
        val groups = UnknownActionGrouping.group(rows)
        assertEquals(listOf("NEW_VERB", "OLD_VERB"), groups.map { it.key })
        assertEquals(5, groups.first { it.key == "OLD_VERB" }.recent.size)
    }

    @Test
    fun `empty input yields no groups`() {
        assertEquals(emptyList<UnknownActionGroup>(), UnknownActionGrouping.group(emptyList()))
    }
}
