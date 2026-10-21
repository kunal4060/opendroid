package com.opendroid.ai.core.templates

import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleSteps(): List<PlanStep> = listOf(
        PlanStep(
            stepId = "step-1",
            order = 1,
            description = "Check the weather",
            action = "GET_WEATHER",
            params = mapOf("city" to "Berlin")
        ),
        PlanStep(
            stepId = "step-2",
            order = 2,
            description = "Message Mom the summary",
            action = "SEND_WHATSAPP",
            params = mapOf("contact" to "Mom", "message" to "\$step-1"),
            dependsOn = listOf("step-1"),
            canParallelize = false,
            fallback = "SEND_SMS"
        )
    )

    private fun sampleMacro(): Macro = Macro(
        id = "macro-42",
        name = "Morning Routine",
        trigger = "cron:0 9 * * 1-5",
        steps = sampleSteps(),
        isSystem = false,
        isEnabled = true
    )

    // ------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------

    @Test
    fun `macro export then decode preserves name trigger and steps`() {
        val export = TemplateCodec.encodeMacro(sampleMacro(), appVersion = "1.0.7", exportedAt = 1234L)

        assertEquals("morning-routine.opendroid.json", export.fileName)
        val decoded = TemplateCodec.decode(export.json)
        assertTrue(decoded is TemplateDecodeResult.Valid)
        decoded as TemplateDecodeResult.Valid

        assertEquals(TemplateFormat.FORMAT_ID, decoded.envelope.format)
        assertEquals(TemplateFormat.CURRENT_SCHEMA_VERSION, decoded.envelope.schemaVersion)
        assertEquals(TemplateFormat.KIND_MACRO, decoded.envelope.kind)
        assertEquals("Morning Routine", decoded.envelope.name)
        assertEquals("cron:0 9 * * 1-5", decoded.envelope.trigger)
        assertEquals(1234L, decoded.envelope.exportedAt)
        assertEquals("1.0.7", decoded.envelope.appVersion)

        // Steps round-trip exactly (params, dependsOn, fallback included):
        // serialising the decoded steps must reproduce the stored stepsJson shape.
        assertEquals(
            json.encodeToString(sampleSteps()),
            json.encodeToString(decoded.preparedSteps)
        )
    }

    @Test
    fun `export strips runtime state from steps`() {
        val dirty = sampleMacro().copy(
            steps = listOf(
                sampleSteps()[0].copy(status = StepStatus.COMPLETED, result = "sunny", error = "boom")
            )
        )
        val decoded = TemplateCodec.decode(TemplateCodec.encodeMacro(dirty, "1.0.7", 0L).json)
        assertTrue(decoded is TemplateDecodeResult.Valid)
        val step = (decoded as TemplateDecodeResult.Valid).preparedSteps.single()
        assertEquals(StepStatus.PENDING, step.status)
        assertEquals(null, step.result)
        assertEquals(null, step.error)
    }

    @Test
    fun `plan export then decode preserves goal duration and steps`() {
        val plan = Plan(
            planId = "plan-9",
            goal = "Plan my Berlin trip",
            estimatedDuration = "10 minutes",
            estimatedSteps = 2,
            steps = sampleSteps(),
            status = PlanStatus.COMPLETED,
            createdAt = 99L
        )
        val decoded = TemplateCodec.decode(TemplateCodec.encodePlan(plan, "1.0.7", 5L).json)
        assertTrue(decoded is TemplateDecodeResult.Valid)
        decoded as TemplateDecodeResult.Valid
        assertEquals(TemplateFormat.KIND_PLAN, decoded.envelope.kind)
        assertEquals("Plan my Berlin trip", decoded.envelope.name)
        assertEquals("10 minutes", decoded.envelope.estimatedDuration)
        assertEquals(json.encodeToString(sampleSteps()), json.encodeToString(decoded.preparedSteps))
    }

    // ------------------------------------------------------------------
    // Malformed / incompatible inputs — all rejected, nothing half-parsed
    // ------------------------------------------------------------------

    @Test
    fun `non json payload is rejected`() {
        val result = TemplateCodec.decode("this is not json")
        assertTrue(result is TemplateDecodeResult.Invalid)
    }

    @Test
    fun `empty payload is rejected`() {
        assertTrue(TemplateCodec.decode("") is TemplateDecodeResult.Invalid)
        assertTrue(TemplateCodec.decode("   ") is TemplateDecodeResult.Invalid)
    }

    @Test
    fun `json without the format marker is rejected`() {
        val result = TemplateCodec.decode("""{"schemaVersion":1,"kind":"macro","name":"X","steps":[]}""")
        assertTrue(result is TemplateDecodeResult.Invalid)
        assertTrue((result as TemplateDecodeResult.Invalid).reason.contains("marker"))
    }

    @Test
    fun `foreign format marker is rejected`() {
        val payload = TemplateCodec.encodeMacro(sampleMacro(), "1.0.7", 0L).json
            .replace(TemplateFormat.FORMAT_ID, "someone-elses-format")
        assertTrue(TemplateCodec.decode(payload) is TemplateDecodeResult.Invalid)
    }

    @Test
    fun `future schema version is rejected with an update message`() {
        val payload = TemplateCodec.encodeMacro(sampleMacro(), "1.0.7", 0L).json
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val result = TemplateCodec.decode(payload)
        assertTrue(result is TemplateDecodeResult.Invalid)
        assertTrue((result as TemplateDecodeResult.Invalid).reason.contains("newer version"))
    }

    @Test
    fun `template with no steps is rejected`() {
        val macro = sampleMacro().copy(steps = emptyList())
        val result = TemplateCodec.decode(TemplateCodec.encodeMacro(macro, "1.0.7", 0L).json)
        assertTrue(result is TemplateDecodeResult.Invalid)
    }

    @Test
    fun `template with blank name is rejected`() {
        val macro = sampleMacro().copy(name = "  ")
        val result = TemplateCodec.decode(TemplateCodec.encodeMacro(macro, "1.0.7", 0L).json)
        assertTrue(result is TemplateDecodeResult.Invalid)
    }

    @Test
    fun `template with duplicate step ids is rejected`() {
        val steps = sampleSteps().map { it.copy(stepId = "same-id") }
        val macro = sampleMacro().copy(steps = steps)
        val result = TemplateCodec.decode(TemplateCodec.encodeMacro(macro, "1.0.7", 0L).json)
        assertTrue(result is TemplateDecodeResult.Invalid)
    }

    @Test
    fun `template with a blank action is rejected`() {
        val steps = listOf(sampleSteps()[0].copy(action = ""))
        val macro = sampleMacro().copy(steps = steps)
        val result = TemplateCodec.decode(TemplateCodec.encodeMacro(macro, "1.0.7", 0L).json)
        assertTrue(result is TemplateDecodeResult.Invalid)
    }

    @Test
    fun `unknown kind is rejected`() {
        val payload = TemplateCodec.encodeMacro(sampleMacro(), "1.0.7", 0L).json
            .replace("\"kind\": \"macro\"", "\"kind\": \"widget\"")
        assertTrue(TemplateCodec.decode(payload) is TemplateDecodeResult.Invalid)
    }

    @Test
    fun `oversized payload is rejected before parsing`() {
        val huge = "{" + "x".repeat(TemplateFormat.MAX_PAYLOAD_CHARS + 1)
        val result = TemplateCodec.decode(huge)
        assertTrue(result is TemplateDecodeResult.Invalid)
        assertTrue((result as TemplateDecodeResult.Invalid).reason.contains("too large"))
    }

    // ------------------------------------------------------------------
    // Warnings: device references + dangling dependency links
    // ------------------------------------------------------------------

    @Test
    fun `contact name reference produces a warning but still decodes`() {
        val decoded = TemplateCodec.decode(TemplateCodec.encodeMacro(sampleMacro(), "1.0.7", 0L).json)
        assertTrue(decoded is TemplateDecodeResult.Valid)
        val warnings = (decoded as TemplateDecodeResult.Valid).warnings
        assertTrue(warnings.any { it.contains("contact 'Mom'") })
    }

    @Test
    fun `phone number contact produces no contact warning`() {
        val steps = listOf(
            sampleSteps()[1].copy(params = mapOf("contact" to "+91 98200 12345", "message" to "hi"), dependsOn = emptyList())
        )
        val decoded = TemplateCodec.decode(
            TemplateCodec.encodeMacro(sampleMacro().copy(steps = steps), "1.0.7", 0L).json
        )
        assertTrue(decoded is TemplateDecodeResult.Valid)
        assertFalse((decoded as TemplateDecodeResult.Valid).warnings.any { it.contains("contact") })
    }

    @Test
    fun `app reference produces a warning`() {
        val steps = listOf(
            PlanStep(stepId = "s1", order = 1, description = "Open WhatsApp", action = "OPEN_APP", params = mapOf("appName" to "WhatsApp"))
        )
        val decoded = TemplateCodec.decode(
            TemplateCodec.encodeMacro(sampleMacro().copy(steps = steps), "1.0.7", 0L).json
        )
        assertTrue(decoded is TemplateDecodeResult.Valid)
        assertTrue((decoded as TemplateDecodeResult.Valid).warnings.any { it.contains("app 'WhatsApp'") })
    }

    @Test
    fun `dangling dependsOn links are dropped and reported`() {
        val steps = listOf(
            sampleSteps()[0],
            sampleSteps()[1].copy(dependsOn = listOf("step-1", "ghost-step"))
        )
        val decoded = TemplateCodec.decode(
            TemplateCodec.encodeMacro(sampleMacro().copy(steps = steps), "1.0.7", 0L).json
        )
        assertTrue(decoded is TemplateDecodeResult.Valid)
        decoded as TemplateDecodeResult.Valid
        assertEquals(listOf("step-1"), decoded.preparedSteps[1].dependsOn)
        assertTrue(decoded.warnings.any { it.contains("not in this template") })
    }

    // ------------------------------------------------------------------
    // prepareImport: registry gate + name collisions
    // ------------------------------------------------------------------

    private fun validDecoded(): TemplateDecodeResult.Valid {
        val decoded = TemplateCodec.decode(TemplateCodec.encodeMacro(sampleMacro(), "1.0.7", 0L).json)
        check(decoded is TemplateDecodeResult.Valid)
        return decoded
    }

    @Test
    fun `prepareImport is ready when all actions are registered`() {
        val plan = TemplateCodec.prepareImport(validDecoded(), existingNames = emptyList()) { null }
        assertTrue(plan is TemplateImportPlan.Ready)
        plan as TemplateImportPlan.Ready
        assertEquals("Morning Routine", plan.resolvedName)
        assertEquals("cron:0 9 * * 1-5", plan.trigger)
        assertEquals(2, plan.steps.size)
    }

    @Test
    fun `prepareImport blocks when any action is unknown`() {
        val plan = TemplateCodec.prepareImport(validDecoded(), existingNames = emptyList()) { step ->
            if (step.action == "SEND_WHATSAPP") "Action 'SEND_WHATSAPP' is not registered." else null
        }
        assertTrue(plan is TemplateImportPlan.Blocked)
        val errors = (plan as TemplateImportPlan.Blocked).errors
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("SEND_WHATSAPP"))
    }

    @Test
    fun `name collisions get an imported suffix and never overwrite`() {
        assertEquals(
            "Morning Routine (imported)",
            TemplateCodec.resolveName("Morning Routine", listOf("Morning Routine"))
        )
        assertEquals(
            "Morning Routine (imported 2)",
            TemplateCodec.resolveName("Morning Routine", listOf("Morning Routine", "Morning Routine (imported)"))
        )
        assertEquals(
            "Morning Routine",
            TemplateCodec.resolveName("Morning Routine", listOf("Evening Routine"))
        )
        // Case-insensitive: an existing lower-case twin still counts as taken.
        assertEquals(
            "Morning Routine (imported)",
            TemplateCodec.resolveName("Morning Routine", listOf("morning routine"))
        )
    }

    @Test
    fun `suggested file names are filesystem friendly`() {
        assertEquals("morning-routine.opendroid.json", TemplateCodec.suggestedFileName("Morning Routine"))
        assertEquals("weird-name.opendroid.json", TemplateCodec.suggestedFileName("  Weird / Name!! "))
        assertEquals("template.opendroid.json", TemplateCodec.suggestedFileName("!!!"))
    }
}
