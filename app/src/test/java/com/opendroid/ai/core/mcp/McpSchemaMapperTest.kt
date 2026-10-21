package com.opendroid.ai.core.mcp

import com.opendroid.ai.core.agent.ParamType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSchemaMapperTest {

    private val schema = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "path":   {"type": "string", "description": "File path"},
            "count":  {"type": "integer", "description": "How many"},
            "ratio":  {"type": "number"},
            "force":  {"type": "boolean", "default": false},
            "mode":   {"type": "string", "enum": ["fast", "full"]},
            "filter": {"type": "object", "properties": {"tag": {"type": "string"}}},
            "ids":    {"type": "array", "items": {"type": "integer"}}
          },
          "required": ["path", "count"]
        }
        """.trimIndent()
    )

    @Test
    fun `schema maps to typed param definitions`() {
        val params = McpSchemaMapper.toParamDefinitions(schema).associateBy { it.name }
        assertEquals(ParamType.STRING, params.getValue("path").type)
        assertTrue(params.getValue("path").required)
        assertEquals(ParamType.INT, params.getValue("count").type)
        assertTrue(params.getValue("count").required)
        // number degrades to STRING with an explicit "JSON number" note
        assertEquals(ParamType.STRING, params.getValue("ratio").type)
        assertTrue(params.getValue("ratio").description.contains("JSON number"))
        assertEquals(ParamType.BOOLEAN, params.getValue("force").type)
        assertEquals(false, params.getValue("force").defaultValue)
        assertEquals(ParamType.ENUM, params.getValue("mode").type)
        assertEquals(listOf("fast", "full"), params.getValue("mode").enumValues)
        // object/array travel as JSON text, and the description says so
        assertEquals(ParamType.STRING, params.getValue("filter").type)
        assertTrue(params.getValue("filter").description.contains("JSON"))
        assertEquals(ParamType.STRING, params.getValue("ids").type)
        assertFalse(params.getValue("mode").required)
    }

    @Test
    fun `string params are coerced to typed json arguments`() {
        val arguments = McpSchemaMapper.toArguments(
            schema,
            mapOf(
                "path" to "/tmp/x",
                "count" to "3",
                "ratio" to "2.5",
                "force" to "true",
                "mode" to "fast",
                "filter" to """{"tag":"a"}""",
                "ids" to "[1, 2]"
            )
        )
        assertEquals("/tmp/x", arguments.getString("path"))
        assertEquals(3L, arguments.getLong("count"))
        assertEquals(2.5, arguments.getDouble("ratio"), 0.0)
        assertTrue(arguments.getBoolean("force"))
        assertEquals("fast", arguments.getString("mode"))
        assertEquals("a", arguments.getJSONObject("filter").getString("tag"))
        assertEquals(2, arguments.getJSONArray("ids").getInt(1))
    }

    @Test
    fun `undeclared params pass through as strings`() {
        val arguments = McpSchemaMapper.toArguments(schema, mapOf("extra" to "hello"))
        assertEquals("hello", arguments.getString("extra"))
    }

    @Test
    fun `bad coercions fail loudly instead of sending garbage`() {
        assertThrows(McpException.Validation::class.java) {
            McpSchemaMapper.toArguments(schema, mapOf("count" to "three"))
        }
        assertThrows(McpException.Validation::class.java) {
            McpSchemaMapper.toArguments(schema, mapOf("force" to "yes"))
        }
        assertThrows(McpException.Validation::class.java) {
            McpSchemaMapper.toArguments(schema, mapOf("filter" to "not json"))
        }
        assertThrows(McpException.Validation::class.java) {
            McpSchemaMapper.toArguments(schema, mapOf("ids" to "{\"a\":1}"))
        }
    }

    @Test
    fun `fingerprint is stable per tool and changes with the contract`() {
        val tool = McpTool("read", "Reads a file", schema)
        val same = McpTool("read", "Reads a file", JSONObject(schema.toString()))
        val changed = McpTool("read", "Reads a file — now with side effects", schema)
        assertEquals(McpSchemaMapper.fingerprint(tool), McpSchemaMapper.fingerprint(same))
        assertTrue(McpSchemaMapper.fingerprint(tool) != McpSchemaMapper.fingerprint(changed))
    }

    @Test
    fun `empty schema yields no params`() {
        assertTrue(McpSchemaMapper.toParamDefinitions(JSONObject()).isEmpty())
        assertEquals(0, McpSchemaMapper.toArguments(JSONObject(), emptyMap()).length())
    }
}
