package com.opendroid.ai.core.agent

import android.content.Context
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DynamicActionRegistryTest {

    private fun definition(name: String, neverAutoApprove: Boolean = true) = ActionDefinition(
        name = name,
        description = "test $name",
        params = emptyList(),
        examples = emptyList(),
        category = ActionCategory.ADVANCED,
        isSimple = false,
        neverAutoApprove = neverAutoApprove
    )

    private fun handler(name: String): Action = object : Action {
        override val name: String = name
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult =
            ActionResult.Success(mapOf("message" to "ran $name"))
    }

    @Before
    fun setUp() = DynamicActionRegistry.clear()

    @Test
    fun `registered definitions are visible to schema lookups`() {
        DynamicActionRegistry.replaceForOwner(
            "mcp:srv",
            mapOf("MCP_SRV_PING" to (definition("MCP_SRV_PING") to handler("MCP_SRV_PING")))
        )
        assertTrue(DynamicActionRegistry.contains("MCP_SRV_PING"))
        assertTrue(ActionSchema.isValid("MCP_SRV_PING"))
        assertEquals("MCP_SRV_PING", ActionSchema.getAction("MCP_SRV_PING")?.name)
        assertTrue(ActionSchema.getAllActionNames().contains("MCP_SRV_PING"))
        assertTrue(ActionSchema.isNeverAutoApprove("MCP_SRV_PING"))
        // Planner prompts list the dynamic tool in its own EXTERNAL section.
        assertTrue(ActionSchema.buildPlanningSchema().contains("MCP_SRV_PING"))
        assertTrue(ActionSchema.buildLLMSchema().contains("EXTERNAL (MCP SERVERS)"))
    }

    @Test
    fun `removing an owner withdraws its tools everywhere`() {
        DynamicActionRegistry.replaceForOwner(
            "mcp:srv",
            mapOf("MCP_SRV_PING" to (definition("MCP_SRV_PING") to handler("MCP_SRV_PING")))
        )
        DynamicActionRegistry.removeForOwner("mcp:srv")
        assertFalse(DynamicActionRegistry.contains("MCP_SRV_PING"))
        assertFalse(ActionSchema.isValid("MCP_SRV_PING"))
        assertNull(DynamicActionRegistry.handlerFor("MCP_SRV_PING"))
        assertFalse(ActionSchema.buildPlanningSchema().contains("MCP_SRV_PING"))
    }

    @Test
    fun `replace swaps a servers whole tool set atomically`() {
        DynamicActionRegistry.replaceForOwner(
            "mcp:srv",
            mapOf("MCP_SRV_OLD" to (definition("MCP_SRV_OLD") to handler("MCP_SRV_OLD")))
        )
        DynamicActionRegistry.replaceForOwner(
            "mcp:srv",
            mapOf("MCP_SRV_NEW" to (definition("MCP_SRV_NEW") to handler("MCP_SRV_NEW")))
        )
        assertFalse(DynamicActionRegistry.contains("MCP_SRV_OLD"))
        assertTrue(DynamicActionRegistry.contains("MCP_SRV_NEW"))
        // Another owner's tools are untouched by the swap.
        DynamicActionRegistry.replaceForOwner(
            "mcp:other",
            mapOf("MCP_OTHER_X" to (definition("MCP_OTHER_X") to handler("MCP_OTHER_X")))
        )
        assertTrue(DynamicActionRegistry.contains("MCP_SRV_NEW"))
        assertTrue(DynamicActionRegistry.contains("MCP_OTHER_X"))
    }

    @Test
    fun `grant flip updates the definition in place`() {
        DynamicActionRegistry.replaceForOwner(
            "mcp:srv",
            mapOf("MCP_SRV_PING" to (definition("MCP_SRV_PING", neverAutoApprove = true) to handler("MCP_SRV_PING")))
        )
        DynamicActionRegistry.updateDefinition(definition("MCP_SRV_PING", neverAutoApprove = false))
        assertFalse(ActionSchema.isNeverAutoApprove("MCP_SRV_PING"))
        // Built-in lookups are unaffected by the overlay.
        assertTrue(ActionSchema.isValid("OPEN_APP"))
    }
}
