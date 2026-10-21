package com.opendroid.ai.ui.screens.graph

import com.opendroid.ai.core.memory.graph.KnowledgeCategory
import com.opendroid.ai.core.memory.graph.KnowledgeNode
import com.opendroid.ai.core.memory.graph.MemoryTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeGraphLayoutTest {

    private fun node(
        id: String,
        tier: MemoryTier,
        accessCount: Int = 3
    ) = KnowledgeNode(
        id = id,
        label = "Node $id",
        category = KnowledgeCategory.NOTE_FACT,
        tier = tier,
        summary = "Summary $id",
        confidence = 0.9f,
        accessCount = accessCount,
        lastUpdated = 1_760_000_000_000L
    )

    /** Synthetic nodes spread evenly across the four tiers, ids sorted-stable. */
    private fun syntheticGraph(total: Int): List<KnowledgeNode> {
        val tiers = MemoryTier.values()
        return (0 until total).map { i ->
            node(
                id = "node_%04d".format(i),
                tier = tiers[i % tiers.size],
                accessCount = (i % 5) + 1
            )
        }
    }

    @Test
    fun `empty graph lays out four zones and no nodes`() {
        val layout = KnowledgeGraphLayout.layout(emptyList())
        assertEquals(4, layout.zones.size)
        assertEquals(MemoryTier.values().toSet(), layout.zones.map { it.tier }.toSet())
        assertTrue(layout.nodes.isEmpty())
    }

    @Test
    fun `single node sits inside its tier zone`() {
        val layout = KnowledgeGraphLayout.layout(
            listOf(node("only", MemoryTier.SENSITIVE))
        )
        val laidOut = layout.nodes.getValue("only")
        val zone = layout.zoneFor(MemoryTier.SENSITIVE)
        assertTrue(laidOut.center.x in zone.left..zone.right)
        assertTrue(laidOut.center.y in zone.top..zone.bottom)
    }

    @Test
    fun `layout is deterministic - same input gives identical positions`() {
        val nodes = syntheticGraph(100)
        val first = KnowledgeGraphLayout.layout(nodes)
        val second = KnowledgeGraphLayout.layout(nodes)
        assertEquals(first.nodes.keys, second.nodes.keys)
        for ((id, laidOut) in first.nodes) {
            assertEquals(laidOut.center, second.nodes.getValue(id).center)
            assertEquals(laidOut.radius, second.nodes.getValue(id).radius, 0f)
        }
    }

    @Test
    fun `layout does not depend on input ordering`() {
        val nodes = syntheticGraph(40)
        val forward = KnowledgeGraphLayout.layout(nodes)
        val reversed = KnowledgeGraphLayout.layout(nodes.reversed())
        for ((id, laidOut) in forward.nodes) {
            assertEquals(laidOut.center, reversed.nodes.getValue(id).center)
        }
    }

    @Test
    fun `every node centre stays inside its own tier zone`() {
        for (total in listOf(10, 100, 300)) {
            val layout = KnowledgeGraphLayout.layout(syntheticGraph(total))
            assertEquals(total, layout.nodes.size)
            for (laidOut in layout.nodes.values) {
                val zone = layout.zoneFor(laidOut.node.tier)
                assertTrue(
                    "node ${laidOut.node.id} x=${laidOut.center.x} outside $zone",
                    laidOut.center.x in zone.left..zone.right
                )
                assertTrue(
                    "node ${laidOut.node.id} y=${laidOut.center.y} outside $zone",
                    laidOut.center.y in zone.top..zone.bottom
                )
            }
        }
    }

    @Test
    fun `ten nodes in one zone settle without overlaps`() {
        val nodes = (0 until 10).map { node("n$it", MemoryTier.LONG_TERM) }
        val layout = KnowledgeGraphLayout.layout(nodes)
        val laid = layout.nodes.values.toList()
        for (i in laid.indices) {
            for (j in i + 1 until laid.size) {
                val dist = laid[i].center.distanceTo(laid[j].center)
                val minDist = laid[i].radius + laid[j].radius
                assertTrue(
                    "nodes ${laid[i].node.id}/${laid[j].node.id} overlap: $dist < $minDist",
                    dist >= minDist - 1f
                )
            }
        }
    }

    /**
     * Overlap audit for larger synthetic graphs: after the bounded settle,
     * the fraction of same-zone pairs still closer than their combined radii
     * must stay small (the anti-hairball promise is a *calm* map, not a
     * physics guarantee at any density).
     */
    @Test
    fun `overlap fraction stays low for 100 and 300 node graphs`() {
        for (total in listOf(100, 300)) {
            val layout = KnowledgeGraphLayout.layout(syntheticGraph(total))
            var pairs = 0
            var overlapping = 0
            val byTier = layout.nodes.values.groupBy { it.node.tier }
            for ((_, zoneNodes) in byTier) {
                for (i in zoneNodes.indices) {
                    for (j in i + 1 until zoneNodes.size) {
                        pairs++
                        val a = zoneNodes[i]
                        val b = zoneNodes[j]
                        val dist = a.center.distanceTo(b.center)
                        if (dist < a.radius + b.radius - 1f) overlapping++
                    }
                }
            }
            val fraction = if (pairs == 0) 0f else overlapping.toFloat() / pairs
            assertTrue(
                "$total nodes: overlap fraction $fraction ($overlapping/$pairs) too high",
                fraction <= 0.05f
            )
        }
    }

    @Test
    fun `radius grows with access count and is capped`() {
        val rare = KnowledgeGraphLayout.radiusFor(node("a", MemoryTier.LONG_TERM, accessCount = 1))
        val frequent = KnowledgeGraphLayout.radiusFor(node("b", MemoryTier.LONG_TERM, accessCount = 10))
        val extreme = KnowledgeGraphLayout.radiusFor(node("c", MemoryTier.LONG_TERM, accessCount = 10_000))
        val cap = KnowledgeGraphLayout.radiusFor(node("d", MemoryTier.LONG_TERM, accessCount = 25))
        assertTrue(frequent > rare)
        assertEquals(cap, extreme, 0f)
        // Sanity: radii stay drawable relative to a zone.
        assertTrue(extreme < KnowledgeGraphLayout.WORLD_HEIGHT / 4f)
    }
}
