package com.opendroid.ai.ui.screens.graph

import com.opendroid.ai.core.memory.graph.KnowledgeNode
import com.opendroid.ai.core.memory.graph.KnowledgeRelation
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.core.memory.graph.PersonalKnowledgeGraph
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The graph visualiser's data path, tested at the stored-format boundary:
 * node rows are the exact JSON the engine writes into the Room `memories`
 * table under `pkg_node_<id>` keys, and relations are the JSON list stored
 * under the `pkg_relations_index` key — decoded here with the same Json
 * configuration `PersonalGrowthEngine` uses, then fed through
 * [GraphRenderModel] exactly as the UI does.
 */
class GraphRenderModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseStoredGraph(
        nodeRows: List<String>,
        relationsRow: String?
    ): PersonalKnowledgeGraph {
        val nodes = nodeRows
            .map { json.decodeFromString<KnowledgeNode>(it) }
            .associateBy { it.id }
        val relations = relationsRow?.let {
            json.decodeFromString<List<KnowledgeRelation>>(it)
        } ?: emptyList()
        return PersonalKnowledgeGraph(nodes = nodes, relations = relations)
    }

    private val coffeeRow = """
        {"id":"explicit_coffee_1760000000000","label":"Coffee","category":"NOTE_FACT",
         "tier":"LONG_TERM","summary":"Likes filter coffee","properties":{},
         "confidence":1.0,"accessCount":4,"lastUpdated":1760000000000,"ttlHours":-1,
         "origin":{"kind":"USER_EXPLICIT","detail":"remember command","createdAt":1760000000000,"sessionId":null}}
    """.trimIndent()

    private val spotifyRow = """
        {"id":"pattern_app_spotify","label":"Spotify","category":"APP_PREFERENCE",
         "tier":"LEARNED_PATTERN","summary":"Preferred music app","properties":{},
         "confidence":0.85,"accessCount":12,"lastUpdated":1760001000000,"ttlHours":-1,
         "origin":{"kind":"APP_USAGE","detail":"music playback","createdAt":1760001000000,"sessionId":null}}
    """.trimIndent()

    private val lockerRow = """
        {"id":"sensitive_locker_code","label":"locker_code","category":"USER_PREFERENCE",
         "tier":"SENSITIVE","summary":"Hardware-encrypted sensitive record (locker_code)",
         "properties":{"key":"locker_code","encrypted":"true"},
         "confidence":1.0,"accessCount":1,"lastUpdated":1760002000000,"ttlHours":-1,
         "origin":{"kind":"USER_ENTERED","detail":"Memory screen · Level 4 encrypted secret","createdAt":1760002000000,"sessionId":null}}
    """.trimIndent()

    @Test
    fun `stored rows parse into a graph the model renders across tiers`() {
        val graph = parseStoredGraph(
            nodeRows = listOf(coffeeRow, spotifyRow, lockerRow),
            relationsRow = null
        )
        val visible = GraphRenderModel.build(
            relations = graph.relations,
            filteredNodes = graph.nodes.values.toList()
        )
        assertEquals(3, visible.nodes.size)
        assertEquals(
            setOf(MemoryTier.LONG_TERM, MemoryTier.LEARNED_PATTERN, MemoryTier.SENSITIVE),
            visible.nodes.map { it.tier }.toSet()
        )
        assertFalse(visible.truncated)
        assertEquals(3, visible.totalCount)
        // Provenance survives the stored-JSON round trip (A7 interlock).
        val spotify = visible.nodes.first { it.id == "pattern_app_spotify" }
        assertEquals("APP_USAGE", spotify.origin?.kind?.name)
    }

    @Test
    fun `legacy node json without origin field still parses`() {
        val legacy = """
            {"id":"explicit_old_1750000000000","label":"Old fact","category":"NOTE_FACT",
             "tier":"LONG_TERM","summary":"Written before provenance existed","properties":{},
             "confidence":1.0,"accessCount":1,"lastUpdated":1750000000000,"ttlHours":-1}
        """.trimIndent()
        val graph = parseStoredGraph(listOf(legacy), relationsRow = null)
        val node = graph.nodes.getValue("explicit_old_1750000000000")
        assertNull(node.origin)
        val visible = GraphRenderModel.build(graph.relations, graph.nodes.values.toList())
        assertEquals(1, visible.nodes.size)
    }

    @Test
    fun `edges keep only relations whose endpoints are both visible`() {
        val relationsJson = """
            [{"sourceId":"explicit_coffee_1760000000000","targetId":"pattern_app_spotify","relationType":"RELATED_TO","weight":0.7},
             {"sourceId":"explicit_coffee_1760000000000","targetId":"deleted_node","relationType":"RELATED_TO","weight":1.0},
             {"sourceId":"pattern_app_spotify","targetId":"pattern_app_spotify","relationType":"SELF","weight":1.0}]
        """.trimIndent()
        val graph = parseStoredGraph(listOf(coffeeRow, spotifyRow), relationsJson)
        val visible = GraphRenderModel.build(graph.relations, graph.nodes.values.toList())

        // Dangling (deleted target) and self-loop relations are dropped;
        // the real stored edge survives.
        assertEquals(1, visible.edges.size)
        val edge = visible.edges.single()
        assertEquals("explicit_coffee_1760000000000", edge.sourceId)
        assertEquals("pattern_app_spotify", edge.targetId)
        assertEquals(0.7f, edge.weight, 0.001f)
    }

    @Test
    fun `edges respect the caller's filter - a filtered-out endpoint drops the edge`() {
        val relationsJson = """
            [{"sourceId":"explicit_coffee_1760000000000","targetId":"pattern_app_spotify","relationType":"RELATED_TO","weight":1.0}]
        """.trimIndent()
        val graph = parseStoredGraph(listOf(coffeeRow, spotifyRow), relationsJson)
        // Caller filtered to LONG_TERM only (as the tier chip does).
        val onlyLongTerm = graph.nodes.values.filter { it.tier == MemoryTier.LONG_TERM }
        val visible = GraphRenderModel.build(graph.relations, onlyLongTerm)
        assertEquals(1, visible.nodes.size)
        assertTrue(visible.edges.isEmpty())
    }

    @Test
    fun `neighbours and edgesOf read the same stored relations`() {
        val relationsJson = """
            [{"sourceId":"explicit_coffee_1760000000000","targetId":"pattern_app_spotify","relationType":"PAIRS_WITH","weight":0.5},
             {"sourceId":"sensitive_locker_code","targetId":"explicit_coffee_1760000000000","relationType":"GUARDS","weight":1.0}]
        """.trimIndent()
        val graph = parseStoredGraph(listOf(coffeeRow, spotifyRow, lockerRow), relationsJson)
        val visible = GraphRenderModel.build(graph.relations, graph.nodes.values.toList())

        assertEquals(
            setOf("pattern_app_spotify", "sensitive_locker_code"),
            GraphRenderModel.neighboursOf(visible, "explicit_coffee_1760000000000")
        )
        assertEquals(
            2,
            GraphRenderModel.edgesOf(visible, "explicit_coffee_1760000000000").size
        )
        assertTrue(GraphRenderModel.edgesOf(visible, "pattern_app_spotify").size == 1)
        val edge = GraphRenderModel.edgesOf(visible, "pattern_app_spotify").single()
        assertEquals(
            "pattern_app_spotify",
            GraphRenderModel.otherEnd(edge, "explicit_coffee_1760000000000")
        )
        // A node with no stored relations honestly has no connections.
        val lonely = GraphRenderModel.build(
            graph.relations,
            listOf(graph.nodes.getValue("sensitive_locker_code"))
        )
        assertTrue(GraphRenderModel.neighboursOf(lonely, "sensitive_locker_code").isEmpty())
    }

    @Test
    fun `visible cap keeps the strongest nodes and reports truncation`() {
        val rows = (0 until GraphRenderModel.MAX_VISIBLE_NODES + 25).map { i ->
            """
            {"id":"bulk_$i","label":"Bulk $i","category":"NOTE_FACT","tier":"LONG_TERM",
             "summary":"bulk","properties":{},"confidence":1.0,"accessCount":$i,
             "lastUpdated":1760000000000,"ttlHours":-1}
            """.trimIndent()
        }
        val graph = parseStoredGraph(rows, relationsRow = null)
        val visible = GraphRenderModel.build(graph.relations, graph.nodes.values.toList())

        assertEquals(GraphRenderModel.MAX_VISIBLE_NODES, visible.nodes.size)
        assertTrue(visible.truncated)
        assertEquals(GraphRenderModel.MAX_VISIBLE_NODES + 25, visible.totalCount)
        // The weakest (lowest accessCount) are the ones dropped.
        val keptAccess = visible.nodes.map { it.accessCount }
        assertEquals(25, keptAccess.minOrNull())
        assertEquals(GraphRenderModel.MAX_VISIBLE_NODES + 24, keptAccess.maxOrNull())
    }

    @Test
    fun `malformed relations row degrades to no edges, not a crash`() {
        // Mirrors the engine's own behaviour: an undecodable index row is
        // treated as "no relations recorded".
        val relations = try {
            json.decodeFromString<List<KnowledgeRelation>>("not json at all")
        } catch (e: Exception) {
            emptyList()
        }
        val graph = parseStoredGraph(listOf(coffeeRow), relationsRow = null)
            .copy(relations = relations)
        val visible = GraphRenderModel.build(graph.relations, graph.nodes.values.toList())
        assertEquals(1, visible.nodes.size)
        assertTrue(visible.edges.isEmpty())
    }

    @Test
    fun `tier display names are plain language`() {
        assertEquals("Temporary", GraphRenderModel.tierDisplayName(MemoryTier.TEMPORARY))
        assertEquals("Long-Term", GraphRenderModel.tierDisplayName(MemoryTier.LONG_TERM))
        assertEquals("Learned Patterns", GraphRenderModel.tierDisplayName(MemoryTier.LEARNED_PATTERN))
        assertEquals("Sensitive", GraphRenderModel.tierDisplayName(MemoryTier.SENSITIVE))
    }
}
