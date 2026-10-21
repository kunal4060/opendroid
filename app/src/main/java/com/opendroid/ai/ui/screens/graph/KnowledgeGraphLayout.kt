package com.opendroid.ai.ui.screens.graph

import com.opendroid.ai.core.memory.graph.KnowledgeNode
import com.opendroid.ai.core.memory.graph.KnowledgeRelation
import com.opendroid.ai.core.memory.graph.MemoryTier
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, deterministic layout + render-model logic for the C5 memory graph
 * visualiser. No Android / Compose types live here so the whole thing is
 * JVM-unit-testable.
 *
 * Design (feature research §7, "no hairball"): the world is divided into one
 * zone per [MemoryTier]; nodes are placed inside their tier's zone on a
 * golden-angle spiral and then settled with a bounded number of repulsion /
 * centring iterations. Given the same node set the result is bit-for-bit
 * identical — no randomness, no wall-clock input — so recompositions and
 * app restarts show the same map.
 */

/** A point in world coordinates. */
data class GraphPoint(val x: Float, val y: Float) {
    operator fun plus(other: GraphPoint): GraphPoint = GraphPoint(x + other.x, y + other.y)
    operator fun minus(other: GraphPoint): GraphPoint = GraphPoint(x - other.x, y - other.y)
    operator fun times(factor: Float): GraphPoint = GraphPoint(x * factor, y * factor)

    fun distanceTo(other: GraphPoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

/** The rectangular world region one tier's nodes live in. */
data class TierZone(
    val tier: MemoryTier,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val center: GraphPoint get() = GraphPoint(left + width / 2f, top + height / 2f)
}

/** A node with its settled world position and draw radius. */
data class LaidOutNode(
    val node: KnowledgeNode,
    val center: GraphPoint,
    val radius: Float
)

/** The complete laid-out graph in world coordinates. */
data class GraphLayout(
    val worldWidth: Float,
    val worldHeight: Float,
    val zones: List<TierZone>,
    val nodes: Map<String, LaidOutNode>
) {
    fun zoneFor(tier: MemoryTier): TierZone = zones.first { it.tier == tier }
    fun nodesInZone(tier: MemoryTier): List<LaidOutNode> =
        nodes.values.filter { it.node.tier == tier }
}

object KnowledgeGraphLayout {

    const val WORLD_WIDTH = 2400f
    const val WORLD_HEIGHT = 1600f

    /** Bounded settle so layout cost stays predictable even for big graphs. */
    const val SETTLE_ITERATIONS = 60

    private const val ZONE_PADDING = 26f
    private const val NODE_GAP = 16f
    private const val GOLDEN_ANGLE = 2.3999631f // π · (3 − √5)

    /** The four tier zones: Temporary · Long-Term on top, Patterns · Sensitive below. */
    fun zones(): List<TierZone> {
        val halfW = WORLD_WIDTH / 2f
        val halfH = WORLD_HEIGHT / 2f
        return listOf(
            TierZone(MemoryTier.TEMPORARY, 0f, 0f, halfW, halfH),
            TierZone(MemoryTier.LONG_TERM, halfW, 0f, halfW, halfH),
            TierZone(MemoryTier.LEARNED_PATTERN, 0f, halfH, halfW, halfH),
            TierZone(MemoryTier.SENSITIVE, halfW, halfH, halfW, halfH)
        )
    }

    /** Node radius grows with how often the memory is actually used. */
    fun radiusFor(node: KnowledgeNode): Float =
        24f + min(node.accessCount, 25) * 1.8f

    /**
     * Lays out [nodes] into tier zones. Deterministic: nodes are processed in
     * id order, the settle loop is fixed-count, and no random or time source
     * is consulted.
     */
    fun layout(nodes: Collection<KnowledgeNode>): GraphLayout {
        val zones = zones()
        val laidOut = LinkedHashMap<String, LaidOutNode>()
        for (zone in zones) {
            val zoneNodes = nodes
                .filter { it.tier == zone.tier }
                .sortedBy { it.id }
            laidOut.putAll(layoutZone(zoneNodes, zone))
        }
        return GraphLayout(WORLD_WIDTH, WORLD_HEIGHT, zones, laidOut)
    }

    private fun layoutZone(
        zoneNodes: List<KnowledgeNode>,
        zone: TierZone
    ): Map<String, LaidOutNode> {
        if (zoneNodes.isEmpty()) return emptyMap()
        val radii = zoneNodes.map { radiusFor(it) }
        val positions = initialPositions(zoneNodes.size, radii, zone)
        settle(positions, radii, zone)
        val result = LinkedHashMap<String, LaidOutNode>(zoneNodes.size)
        zoneNodes.forEachIndexed { index, node ->
            result[node.id] = LaidOutNode(node, positions[index], radii[index])
        }
        return result
    }

    /**
     * Golden-angle spiral seed: index-ordered, evenly spread, and already
     * close to the final arrangement so the settle loop only has to polish.
     */
    private fun initialPositions(
        count: Int,
        radii: List<Float>,
        zone: TierZone
    ): MutableList<GraphPoint> {
        val center = zone.center
        val maxRadius = radii.max()
        val reach = min(zone.width, zone.height) / 2f - ZONE_PADDING - maxRadius
        val safeReach = if (reach < 10f) 10f else reach
        val spacing = if (count <= 1) 0f else safeReach / sqrt(count.toFloat())
        return MutableList(count) { index ->
            if (count == 1) {
                center
            } else {
                val r = spacing * sqrt(index + 0.5f)
                val theta = index * GOLDEN_ANGLE
                GraphPoint(
                    x = center.x + r * cos(theta),
                    y = center.y + r * sin(theta) * (zone.height / zone.width)
                )
            }
        }
    }

    /**
     * Bounded force settle: same-zone pairwise repulsion against overlap plus
     * a gentle pull to the zone centre, positions clamped inside the zone
     * after every iteration. Runs exactly [SETTLE_ITERATIONS] rounds.
     */
    private fun settle(
        positions: MutableList<GraphPoint>,
        radii: List<Float>,
        zone: TierZone
    ) {
        val center = zone.center
        repeat(SETTLE_ITERATIONS) {
            // Repulsion: push overlapping pairs apart, half the overlap each.
            for (i in positions.indices) {
                for (j in i + 1 until positions.size) {
                    val a = positions[i]
                    val b = positions[j]
                    val minDist = radii[i] + radii[j] + NODE_GAP
                    val delta = b - a
                    val dist = a.distanceTo(b)
                    if (dist >= minDist) continue
                    val direction = if (dist > 0.01f) {
                        delta * (1f / dist)
                    } else {
                        // Coincident seeds: pick a stable direction from the
                        // pair's indices so the result stays deterministic.
                        val theta = (i * 31 + j) * GOLDEN_ANGLE
                        GraphPoint(cos(theta), sin(theta))
                    }
                    val push = direction * ((minDist - dist) / 2f * 0.5f)
                    positions[i] = a - push
                    positions[j] = b + push
                }
            }
            // Centring + clamp.
            for (i in positions.indices) {
                val pulled = positions[i] + (center - positions[i]) * 0.015f
                positions[i] = clampToZone(pulled, radii[i], zone)
            }
        }
    }

    private fun clampToZone(point: GraphPoint, radius: Float, zone: TierZone): GraphPoint {
        val inset = ZONE_PADDING + radius
        val minX = zone.left + inset
        val maxX = zone.right - inset
        val minY = zone.top + inset
        val maxY = zone.bottom - inset
        // A node larger than the zone itself just sits at the centre.
        val x = if (minX > maxX) zone.center.x else point.x.coerceIn(minX, maxX)
        val y = if (minY > maxY) zone.center.y else point.y.coerceIn(minY, maxY)
        return GraphPoint(x, y)
    }
}

/**
 * The render-ready slice of the graph: which nodes are on screen and which
 * stored relations connect them. Relations come from the stored
 * `pkg_relations_index` JSON (via `PersonalGrowthEngine.graphFlow`) — this
 * object never invents an edge; a relation whose endpoints are not both
 * visible (or that points at a deleted node) is dropped.
 */
object GraphRenderModel {

    /**
     * Anti-hairball / performance cap (research §7): beyond this many nodes
     * the visualiser shows the strongest ones and says so.
     */
    const val MAX_VISIBLE_NODES = 150

    data class VisibleGraph(
        val nodes: List<KnowledgeNode>,
        val edges: List<KnowledgeRelation>,
        /** True when [nodes] was capped; [totalCount] is the pre-cap count. */
        val truncated: Boolean,
        val totalCount: Int
    )

    /**
     * Builds the visible graph from the already-filtered node list (search /
     * tier / category filters are applied by the caller, exactly as the list
     * view applies them) and the graph's full stored relation list.
     */
    fun build(
        relations: List<KnowledgeRelation>,
        filteredNodes: List<KnowledgeNode>
    ): VisibleGraph {
        val strongestFirst = filteredNodes.sortedWith(
            compareByDescending<KnowledgeNode> { it.accessCount }
                .thenByDescending { it.lastUpdated }
                .thenBy { it.id }
        )
        val kept = strongestFirst.take(MAX_VISIBLE_NODES)
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val edges = relations
            .filter {
                it.sourceId in keptIds && it.targetId in keptIds &&
                    it.sourceId != it.targetId
            }
            // Dedup by (source, target, type) — the same identity
            // PersonalKnowledgeGraph.addRelation uses. Full-object distinct()
            // let an imported triple with two different weights through as
            // two edges (duplicate "Connections" rows, double-drawn edges).
            .distinctBy { Triple(it.sourceId, it.targetId, it.relationType) }
        return VisibleGraph(
            nodes = kept,
            edges = edges,
            truncated = strongestFirst.size > kept.size,
            totalCount = strongestFirst.size
        )
    }

    /** Ids directly connected to [nodeId] inside the visible graph. */
    fun neighboursOf(visible: VisibleGraph, nodeId: String): Set<String> {
        val result = LinkedHashSet<String>()
        for (edge in visible.edges) {
            if (edge.sourceId == nodeId) result.add(edge.targetId)
            if (edge.targetId == nodeId) result.add(edge.sourceId)
        }
        return result
    }

    /** Stored relations touching [nodeId] inside the visible graph. */
    fun edgesOf(visible: VisibleGraph, nodeId: String): List<KnowledgeRelation> =
        visible.edges.filter { it.sourceId == nodeId || it.targetId == nodeId }

    /** The id at the other end of [edge] from [nodeId]'s perspective. */
    fun otherEnd(edge: KnowledgeRelation, nodeId: String): String =
        if (edge.sourceId == nodeId) edge.targetId else edge.sourceId

    /** Plain-language tier names for zone headers and detail views. */
    fun tierDisplayName(tier: MemoryTier): String = when (tier) {
        MemoryTier.TEMPORARY -> "Temporary"
        MemoryTier.LONG_TERM -> "Long-Term"
        MemoryTier.LEARNED_PATTERN -> "Learned Patterns"
        MemoryTier.SENSITIVE -> "Sensitive"
    }
}
