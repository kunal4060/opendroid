package com.opendroid.ai.core.mcp

import com.opendroid.ai.core.agent.ParamDefinition
import com.opendroid.ai.core.agent.ParamType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges MCP's JSON Schema world and the app's stringly-typed params.
 *
 * Inbound (planner side): a tool's `inputSchema` becomes [ParamDefinition]s
 * so the existing schema validation, defaults and prompt builders treat the
 * tool like any other action. Outbound (execution side): the
 * `Map<String, String>` the dispatcher hands over is coerced back into a
 * typed JSON `arguments` object using the declared schema — integers and
 * booleans are parsed for real, numbers keep their JSON form, and
 * object/array params travel as raw JSON text (called out in the generated
 * param description so the planner knows to pass JSON).
 *
 * Pure — unit-tested without Android.
 */
object McpSchemaMapper {

    private const val JSON_TEXT_NOTE = "Pass as JSON text."

    /** JSON Schema → planner-facing param definitions. */
    fun toParamDefinitions(inputSchema: JSONObject): List<ParamDefinition> {
        val properties = inputSchema.optJSONObject("properties") ?: return emptyList()
        val required = requiredNames(inputSchema)
        return properties.keys().asSequence().map { name ->
            val property = properties.optJSONObject(name) ?: JSONObject()
            paramDefinition(name, property, required = name in required)
        }.toList()
    }

    private fun paramDefinition(name: String, property: JSONObject, required: Boolean): ParamDefinition {
        val type = property.optString("type", "string")
        val rawDescription = property.optString("description")
        val enumValues = property.optJSONArray("enum")?.let { array ->
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty()
        val default = property.opt("default")
        return when {
            enumValues.isNotEmpty() -> ParamDefinition(
                name = name,
                type = ParamType.ENUM,
                required = required,
                description = rawDescription,
                enumValues = enumValues,
                defaultValue = default?.takeIf { it != JSONObject.NULL }?.toString()
            )
            type == "integer" -> ParamDefinition(
                name = name,
                type = ParamType.INT,
                required = required,
                description = rawDescription,
                defaultValue = (default as? Number)?.toInt()
            )
            type == "boolean" -> ParamDefinition(
                name = name,
                type = ParamType.BOOLEAN,
                required = required,
                description = rawDescription,
                defaultValue = default as? Boolean
            )
            type == "number" -> ParamDefinition(
                name = name,
                type = ParamType.STRING,
                required = required,
                description = note(rawDescription, "Pass as a JSON number, e.g. 3.5."),
                defaultValue = (default as? Number)?.toString()
            )
            type == "object" || type == "array" -> ParamDefinition(
                name = name,
                type = ParamType.STRING,
                required = required,
                description = note(rawDescription, "$JSON_TEXT_NOTE Expected shape: ${compactSchema(property)}"),
                defaultValue = null
            )
            else -> ParamDefinition(
                name = name,
                type = ParamType.STRING,
                required = required,
                description = rawDescription,
                defaultValue = (default as? String)
            )
        }
    }

    /**
     * Coerces dispatcher params into the tool's JSON `arguments` object.
     * Declared params are typed per the schema; undeclared params pass
     * through as strings (the server is the authority on its own schema and
     * answers with a proper error). Throws [McpException.Validation] on a
     * value that cannot be coerced — the tool is never called with garbage.
     */
    fun toArguments(inputSchema: JSONObject, params: Map<String, String>): JSONObject {
        val properties = inputSchema.optJSONObject("properties") ?: JSONObject()
        val arguments = JSONObject()
        for ((name, rawValue) in params) {
            val property = properties.optJSONObject(name)
            if (property == null) {
                arguments.put(name, rawValue)
                continue
            }
            arguments.put(name, coerce(name, property, rawValue))
        }
        return arguments
    }

    private fun coerce(name: String, property: JSONObject, rawValue: String): Any {
        if (property.has("enum")) return rawValue
        return when (property.optString("type", "string")) {
            "integer" -> rawValue.trim().toLongOrNull()
                ?: throw McpException.Validation("Parameter '$name' must be an integer, got '$rawValue'")
            "number" -> rawValue.trim().toDoubleOrNull()
                ?: throw McpException.Validation("Parameter '$name' must be a number, got '$rawValue'")
            "boolean" -> when (rawValue.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw McpException.Validation("Parameter '$name' must be true or false, got '$rawValue'")
            }
            "object" -> try {
                JSONObject(rawValue)
            } catch (_: Exception) {
                throw McpException.Validation("Parameter '$name' must be a JSON object")
            }
            "array" -> try {
                JSONArray(rawValue)
            } catch (_: Exception) {
                throw McpException.Validation("Parameter '$name' must be a JSON array")
            }
            else -> rawValue
        }
    }

    /** Stable fingerprint of a tool's identity + contract, for rug-pull detection. */
    fun fingerprint(tool: McpTool): String {
        val material = "${tool.name}|${tool.description}|${tool.inputSchema}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun requiredNames(inputSchema: JSONObject): Set<String> {
        val required = inputSchema.optJSONArray("required") ?: return emptySet()
        return (0 until required.length()).map { required.optString(it) }.toSet()
    }

    private fun note(description: String, note: String): String =
        listOf(description, note).filter { it.isNotBlank() }.joinToString(" ")

    private fun compactSchema(property: JSONObject): String {
        val text = property.toString()
        return if (text.length <= 160) text else text.take(157) + "…"
    }
}
