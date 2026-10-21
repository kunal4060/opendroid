package com.opendroid.ai.core.mcp

import java.security.MessageDigest

/**
 * Namespacing for MCP tools in the app's UPPER_SNAKE action world.
 *
 * A tool becomes the action `MCP_<SERVER>_<TOOL>` (upper-cased,
 * non-alphanumerics folded to `_`). The planner, PlanValidator, audit log
 * and the unknown-action loop all treat action names as UPPER_SNAKE tokens,
 * so external tools adopt the same shape instead of smuggling a foreign
 * `server__tool` dialect through the normaliser — and the `MCP_` prefix
 * makes external steps recognisable everywhere they surface.
 */
object McpNames {

    const val PREFIX = "MCP"

    /** One name segment: upper-cased, [A-Z0-9] kept, everything else → `_`, repeats collapsed. */
    fun sanitizeSegment(raw: String): String {
        val folded = raw.uppercase()
            .map { ch -> if (ch.isLetterOrDigit()) ch else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
        return folded.ifBlank { "X" }
    }

    /** The action name for [toolName] on [serverName] before collision handling. */
    fun baseActionName(serverName: String, toolName: String): String =
        "${PREFIX}_${sanitizeSegment(serverName)}_${sanitizeSegment(toolName)}"

    /**
     * Assigns unique action names to one server's tools. Two different tool
     * names can sanitise to the same token (`get-file` vs `get_file`); the
     * first keeps the plain name, later ones gain a short stable hash
     * suffix derived from the original tool name.
     */
    fun assignActionNames(serverName: String, toolNames: List<String>): Map<String, String> {
        val taken = mutableSetOf<String>()
        val assigned = LinkedHashMap<String, String>()
        for (toolName in toolNames) {
            var candidate = baseActionName(serverName, toolName)
            if (candidate in taken) {
                candidate = "${candidate}_${shortHash(toolName)}"
                var attempt = 2
                while (candidate in taken) {
                    candidate = "${baseActionName(serverName, toolName)}_${shortHash(toolName)}_$attempt"
                    attempt++
                }
            }
            taken += candidate
            assigned[toolName] = candidate
        }
        return assigned
    }

    fun isMcpActionName(actionName: String): Boolean = actionName.startsWith("${PREFIX}_")

    /**
     * Prefix of every grant-map key belonging to [serverName]: action
     * names are `MCP_<sanitized server>_<tool>` (collision-suffixed ones
     * included), so a server's always-allow grants are exactly the keys
     * with this prefix. Used to revoke them all when the server goes away.
     */
    fun grantKeyPrefix(serverName: String): String = "${PREFIX}_${sanitizeSegment(serverName)}_"

    private fun shortHash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(3).joinToString("") { "%02X".format(it) }
    }
}
