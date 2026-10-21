package com.opendroid.ai.core.privacy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wipe's honesty contract: every target is attempted in order, failures are recorded
 * with their reason (never swallowed), and [WipeReport.fullyErased] is only true when
 * literally every target cleared.
 */
class WipeOrchestratorTest {

    private fun target(
        id: String,
        calls: MutableList<String>,
        failure: Exception? = null
    ): WipeTarget = WipeTarget(
        id = id,
        title = "Title $id",
        remainingDescription = "Remaining $id",
        clear = {
            calls += id
            if (failure != null) throw failure
        }
    )

    @Test
    fun `all targets cleared - report is fully erased and order is preserved`() = runBlocking {
        val calls = mutableListOf<String>()
        val orchestrator = WipeOrchestrator(
            listOf(target("a", calls), target("b", calls), target("c", calls))
        )

        val report = orchestrator.execute()

        assertEquals(listOf("a", "b", "c"), calls)
        assertTrue(report.fullyErased)
        assertTrue(report.failedSteps.isEmpty())
        assertEquals(listOf("a", "b", "c"), report.steps.map { it.targetId })
        assertTrue(report.steps.all { it.cleared })
        assertTrue(report.steps.all { it.errorMessage == null })
    }

    @Test
    fun `a failing target is reported and later targets still run`() = runBlocking {
        val calls = mutableListOf<String>()
        val orchestrator = WipeOrchestrator(
            listOf(
                target("a", calls),
                target("b", calls, failure = IllegalStateException("store locked")),
                target("c", calls)
            )
        )

        val report = orchestrator.execute()

        // The failure must not stop the wipe: every later area is still attempted.
        assertEquals(listOf("a", "b", "c"), calls)
        assertFalse(report.fullyErased)
        assertEquals(listOf("b"), report.failedSteps.map { it.targetId })
        val failed = report.failedSteps.single()
        assertFalse(failed.cleared)
        assertEquals("store locked", failed.errorMessage)
        assertEquals("Remaining b", failed.remainingDescription)
    }

    @Test
    fun `first target failing does not skip the rest`() = runBlocking {
        val calls = mutableListOf<String>()
        val orchestrator = WipeOrchestrator(
            listOf(
                target("a", calls, failure = RuntimeException("boom")),
                target("b", calls)
            )
        )

        val report = orchestrator.execute()

        assertEquals(listOf("a", "b"), calls)
        assertFalse(report.fullyErased)
        assertTrue(report.steps[1].cleared)
    }

    @Test
    fun `no targets - vacuously fully erased`() = runBlocking {
        val report = WipeOrchestrator(emptyList()).execute()

        assertTrue(report.fullyErased)
        assertTrue(report.steps.isEmpty())
    }

    @Test
    fun `progress callback receives each finished step in order`() = runBlocking {
        val calls = mutableListOf<String>()
        val progressed = mutableListOf<WipeStepResult>()
        val orchestrator = WipeOrchestrator(
            listOf(
                target("a", calls),
                target("b", calls, failure = IllegalStateException("nope")),
                target("c", calls)
            )
        )

        val report = orchestrator.execute { step -> progressed += step }

        assertEquals(report.steps, progressed)
        assertEquals(listOf("a", "b", "c"), progressed.map { it.targetId })
        assertEquals(listOf(true, false, true), progressed.map { it.cleared })
        assertNull(progressed[0].errorMessage)
        assertEquals("nope", progressed[1].errorMessage)
    }

    @Test
    fun `failure without a message falls back to the exception name`() = runBlocking {
        val calls = mutableListOf<String>()
        val orchestrator = WipeOrchestrator(
            listOf(target("a", calls, failure = IllegalStateException()))
        )

        val report = orchestrator.execute()

        assertFalse(report.fullyErased)
        assertEquals("IllegalStateException", report.failedSteps.single().errorMessage)
    }
}
