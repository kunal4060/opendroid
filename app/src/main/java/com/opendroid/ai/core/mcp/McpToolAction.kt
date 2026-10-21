package com.opendroid.ai.core.mcp

import android.content.Context
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult

/**
 * The synthetic dispatcher [Action] behind every registered MCP tool.
 *
 * Execution is a straight line: stringly params are coerced to typed JSON
 * arguments per the tool's own schema, the call goes through
 * [McpClientManager] (which re-checks server + tool enablement against the
 * live config before sending), and the tool's content comes back as an
 * ordinary [ActionResult] — text the planner treats as untrusted tool
 * output, exactly like web-search results. An MCP `isError` result becomes
 * a failed action, never a fake success.
 */
class McpToolAction(
    override val name: String,
    private val serverName: String,
    private val tool: McpTool,
    private val manager: McpClientManager
) : Action {

    override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
        val arguments = try {
            McpSchemaMapper.toArguments(tool.inputSchema, params)
        } catch (error: McpException.Validation) {
            return ActionResult.Failure(
                errorMsg = error.message ?: "Invalid arguments for $name",
                fallback = "Check the parameter types for this tool"
            )
        }
        return try {
            val outcome = manager.callTool(serverName, tool.name, arguments)
            val text = capResultText(outcome.text)
            if (outcome.isError) {
                ActionResult.Failure(
                    errorMsg = text,
                    fallback = "The MCP tool '$name' reported an error"
                )
            } else {
                ActionResult.Success(
                    dataMap = mapOf(
                        "message" to text,
                        "tool" to tool.name,
                        "server" to serverName,
                        "source" to "mcp"
                    )
                )
            }
        } catch (error: McpException) {
            ActionResult.Failure(
                errorMsg = error.message ?: "MCP call to $name failed",
                fallback = "The MCP server may be unreachable; continue with built-in tools"
            )
        }
    }

    /** Tool output feeds the planner's context — cap it so one verbose tool can't flood a plan. */
    private fun capResultText(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text
        else text.take(MAX_RESULT_CHARS) + "\n… [tool output truncated at $MAX_RESULT_CHARS characters]"

    private companion object {
        const val MAX_RESULT_CHARS = 16_000
    }
}
