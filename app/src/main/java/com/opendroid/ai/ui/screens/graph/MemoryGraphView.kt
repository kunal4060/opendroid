package com.opendroid.ai.ui.screens.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.memory.graph.KnowledgeCategory
import com.opendroid.ai.core.memory.graph.KnowledgeNode
import com.opendroid.ai.core.memory.graph.KnowledgeRelation
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.JetBrainsMonoFamily
import com.opendroid.ai.ui.theme.MonoMedium
import com.opendroid.ai.ui.viewmodel.MemoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MIN_SCALE = 0.12f
private const val MAX_SCALE = 3.5f

/** Fill colour per knowledge category (node meaning is also in its label). */
@Composable
private fun categoryColor(category: KnowledgeCategory): Color = when (category) {
    KnowledgeCategory.CONTACT -> AppTheme.colors.accentInfo
    KnowledgeCategory.TASK_ROUTINE -> AppTheme.colors.accentGreenButton
    KnowledgeCategory.APP_PREFERENCE -> AppTheme.colors.textSecondary
    KnowledgeCategory.SCHEDULE -> AppTheme.colors.accentOrange
    KnowledgeCategory.PROJECT -> AppTheme.colors.accentNeonGreen
    KnowledgeCategory.RESOURCE -> AppTheme.colors.textPrimary
    KnowledgeCategory.NOTE_FACT -> AppTheme.colors.accentInfo.copy(alpha = 0.55f)
    KnowledgeCategory.USER_PREFERENCE -> AppTheme.colors.accentNeonGreen.copy(alpha = 0.55f)
}

/** Accent per tier, used for zone framing and pattern confidence arcs. */
@Composable
private fun tierAccent(tier: MemoryTier): Color = when (tier) {
    MemoryTier.TEMPORARY -> AppTheme.colors.textSecondary
    MemoryTier.LONG_TERM -> AppTheme.colors.accentNeonGreen
    MemoryTier.LEARNED_PATTERN -> AppTheme.colors.accentInfo
    MemoryTier.SENSITIVE -> AppTheme.colors.accentOrange
}

/** What a node may show as its name. Sensitive records never show theirs on the map. */
fun graphDisplayLabel(node: KnowledgeNode): String =
    if (node.tier == MemoryTier.SENSITIVE) "Locked record" else node.label

/**
 * The C5 memory graph visualiser: the real [PersonalKnowledgeGraph] (already
 * filtered by the caller exactly as the list view filters it) rendered as a
 * calm, tier-zoned map on a Compose Canvas.
 *
 * - Layout is [KnowledgeGraphLayout]: deterministic, computed off the main
 *   thread, cached until the node set actually changes.
 * - Edges follow the anti-hairball rule: stored relations are drawn only for
 *   the focused node, never all at once.
 * - Level-4 (Sensitive) nodes are locked tiles: no label, no content on the
 *   canvas; their secret is revealed only inside the detail sheet after the
 *   app-lock authentication prompt.
 */
@Composable
fun MemoryGraphView(
    viewModel: MemoryViewModel,
    filteredNodes: List<KnowledgeNode>,
    relations: List<KnowledgeRelation>,
    totalNodeCount: Int,
    modifier: Modifier = Modifier
) {
    // Honest empty states — never sample nodes.
    if (filteredNodes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = AppTheme.colors.textTertiary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (totalNodeCount == 0) {
                        "Your knowledge graph is empty.\nMemories you save and patterns I learn will appear here as a map."
                    } else {
                        "No memories match the current search or filters."
                    },
                    color = AppTheme.colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
        return
    }

    val visible = remember(relations, filteredNodes) {
        GraphRenderModel.build(relations, filteredNodes)
    }

    // Layout is pure CPU work; settle it off the main thread and only when
    // the laid-out content (ids, tiers, importance) actually changes.
    var layout by remember { mutableStateOf<GraphLayout?>(null) }
    val layoutKey = remember(visible.nodes) {
        visible.nodes.joinToString("|") {
            "${it.id}:${it.tier}:${it.accessCount}:${it.confidence}:" +
                "${it.label.hashCode()}:${it.summary.hashCode()}"
        }
    }
    LaunchedEffect(layoutKey) {
        val nodesSnapshot = visible.nodes
        layout = withContext(Dispatchers.Default) {
            KnowledgeGraphLayout.layout(nodesSnapshot)
        }
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(0.3f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewInitialised by remember { mutableStateOf(false) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    // Drop focus/selection when the node leaves the visible set (deleted or
    // filtered out) so the map never points at a record that is gone.
    LaunchedEffect(visible) {
        if (focusedId != null && visible.nodes.none { it.id == focusedId }) focusedId = null
        if (selectedId != null && visible.nodes.none { it.id == selectedId }) selectedId = null
    }

    // Centre the focused node whenever focus changes.
    val currentLayout by rememberUpdatedState(layout)
    LaunchedEffect(focusedId) {
        val id = focusedId ?: return@LaunchedEffect
        val laidOut = currentLayout?.nodes?.get(id) ?: return@LaunchedEffect
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        offset = Offset(
            x = canvasSize.width / 2f - laidOut.center.x * scale,
            y = canvasSize.height / 2f - laidOut.center.y * scale
        )
    }

    // Resolve theme colours here — the Canvas draw lambda is not composable.
    val background = AppTheme.colors.background
    val cardBackground = AppTheme.colors.cardBackground
    val borderColor = AppTheme.colors.borderColor
    val textPrimary = AppTheme.colors.textPrimary
    val textSecondary = AppTheme.colors.textSecondary
    val accent = AppTheme.colors.accentNeonGreen
    val accentOrange = AppTheme.colors.accentOrange
    val categoryColors = KnowledgeCategory.values().associateWith { categoryColor(it) }
    val tierAccents = MemoryTier.values().associateWith { tierAccent(it) }
    val textMeasurer = rememberTextMeasurer()

    val focusNeighbours = remember(visible, focusedId) {
        focusedId?.let { GraphRenderModel.neighboursOf(visible, it) } ?: emptySet()
    }
    val focusEdges = remember(visible, focusedId) {
        focusedId?.let { GraphRenderModel.edgesOf(visible, it) } ?: emptyList()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .semantics {
                // Enriched summary (D8): per-tier counts from the same
                // filtered node set the canvas draws; sensitive memories
                // are counted but never named here (they render locked).
                contentDescription =
                    "Knowledge graph map: ${visible.nodes.size} memories — " +
                        "Long-Term ${visible.nodes.count { it.tier == MemoryTier.LONG_TERM }}, " +
                        "Learned Patterns ${visible.nodes.count { it.tier == MemoryTier.LEARNED_PATTERN }}, " +
                        "Temporary ${visible.nodes.count { it.tier == MemoryTier.TEMPORARY }}, " +
                        "Sensitive ${visible.nodes.count { it.tier == MemoryTier.SENSITIVE }} (shown locked). " +
                        "The List view shows the same memories in screen-reader friendly form."
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    canvasSize = size
                    if (!viewInitialised && size != IntSize.Zero) {
                        val fit = minOf(
                            size.width / KnowledgeGraphLayout.WORLD_WIDTH,
                            size.height / KnowledgeGraphLayout.WORLD_HEIGHT
                        )
                        scale = fit
                        offset = Offset(
                            x = (size.width - KnowledgeGraphLayout.WORLD_WIDTH * fit) / 2f,
                            y = (size.height - KnowledgeGraphLayout.WORLD_HEIGHT * fit) / 2f
                        )
                        viewInitialised = true
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val oldScale = scale
                        val newScale = (oldScale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        offset = Offset(
                            x = centroid.x - (centroid.x - offset.x) * (newScale / oldScale) + pan.x,
                            y = centroid.y - (centroid.y - offset.y) * (newScale / oldScale) + pan.y
                        )
                        scale = newScale
                    }
                }
                .pointerInput(layout, scale, offset) {
                    detectTapGestures { tap ->
                        val current = layout ?: return@detectTapGestures
                        val world = GraphPoint(
                            x = (tap.x - offset.x) / scale,
                            y = (tap.y - offset.y) / scale
                        )
                        val hit = current.nodes.values
                            .minByOrNull { it.center.distanceTo(world) }
                            ?.takeIf {
                                it.center.distanceTo(world) <= it.radius + 14f / scale
                            }
                        if (hit != null) {
                            focusedId = hit.node.id
                            selectedId = hit.node.id
                        } else {
                            focusedId = null
                        }
                    }
                }
        ) {
            val current = layout ?: return@Canvas
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, Offset.Zero)
            }) {
                // ── Tier zones ──────────────────────────────────────────
                for (zone in current.zones) {
                    val accent = tierAccents.getValue(zone.tier)
                    val topLeft = Offset(zone.left + 10f, zone.top + 10f)
                    val zoneSize = Size(zone.width - 20f, zone.height - 20f)
                    drawRoundRect(
                        color = cardBackground.copy(alpha = 0.55f),
                        topLeft = topLeft,
                        size = zoneSize,
                        cornerRadius = CornerRadius(28f, 28f)
                    )
                    drawRoundRect(
                        color = accent.copy(alpha = 0.45f),
                        topLeft = topLeft,
                        size = zoneSize,
                        cornerRadius = CornerRadius(28f, 28f),
                        style = Stroke(width = 2f / scale)
                    )
                    val zoneNodes = current.nodesInZone(zone.tier)
                    val header = textMeasurer.measure(
                        text = "${GraphRenderModel.tierDisplayName(zone.tier).uppercase()} · ${zoneNodes.size}",
                        style = TextStyle(
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JetBrainsMonoFamily,
                            color = accent
                        )
                    )
                    drawText(
                        header,
                        color = accent,
                        topLeft = Offset(zone.left + 34f, zone.top + 26f)
                    )
                    if (zoneNodes.isEmpty()) {
                        val empty = textMeasurer.measure(
                            text = "Nothing here yet",
                            style = TextStyle(
                                fontSize = 30.sp,
                                color = textSecondary
                            )
                        )
                        drawText(
                            empty,
                            color = textSecondary,
                            topLeft = Offset(
                                x = zone.center.x - empty.size.width / 2f,
                                y = zone.center.y - empty.size.height / 2f
                            )
                        )
                    }
                }

                // ── Edges: focus mode only (anti-hairball rule) ─────────
                val focusedLayout = focusedId?.let { current.nodes[it] }
                if (focusedLayout != null) {
                    for (edge in focusEdges) {
                        val other = current.nodes[GraphRenderModel.otherEnd(edge, focusedLayout.node.id)]
                            ?: continue
                        drawLine(
                            color = accent.copy(
                                alpha = 0.25f + 0.55f * edge.weight.coerceIn(0f, 1f)
                            ),
                            start = Offset(focusedLayout.center.x, focusedLayout.center.y),
                            end = Offset(other.center.x, other.center.y),
                            strokeWidth = 3f / scale
                        )
                    }
                }

                // ── Nodes ───────────────────────────────────────────────
                for (laidOut in current.nodes.values) {
                    val node = laidOut.node
                    val center = Offset(laidOut.center.x, laidOut.center.y)
                    val dimmed = focusedId != null &&
                        node.id != focusedId && node.id !in focusNeighbours
                    val baseAlpha = if (dimmed) 0.16f else 1f

                    if (node.tier == MemoryTier.SENSITIVE) {
                        // Locked tile: shape + lock glyph only, no label,
                        // no content — the secret never reaches the canvas.
                        val tile = laidOut.radius * 1.75f
                        drawRoundRect(
                            color = accentOrange.copy(alpha = 0.28f * baseAlpha),
                            topLeft = Offset(center.x - tile / 2f, center.y - tile / 2f),
                            size = Size(tile, tile),
                            cornerRadius = CornerRadius(14f, 14f)
                        )
                        drawRoundRect(
                            color = accentOrange.copy(alpha = 0.8f * baseAlpha),
                            topLeft = Offset(center.x - tile / 2f, center.y - tile / 2f),
                            size = Size(tile, tile),
                            cornerRadius = CornerRadius(14f, 14f),
                            style = Stroke(width = 2.5f / scale)
                        )
                        // Lock body + shackle drawn from primitives.
                        val bodyW = laidOut.radius * 0.62f
                        val bodyH = laidOut.radius * 0.52f
                        drawRoundRect(
                            color = accentOrange.copy(alpha = 0.9f * baseAlpha),
                            topLeft = Offset(center.x - bodyW / 2f, center.y - bodyH * 0.1f),
                            size = Size(bodyW, bodyH),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                        drawArc(
                            color = accentOrange.copy(alpha = 0.9f * baseAlpha),
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(
                                center.x - bodyW * 0.32f,
                                center.y - bodyH * 0.72f
                            ),
                            size = Size(bodyW * 0.64f, bodyH * 0.8f),
                            style = Stroke(width = laidOut.radius * 0.09f)
                        )
                        continue
                    }

                    val fill = categoryColors.getValue(node.category)
                    drawCircle(
                        color = fill.copy(alpha = 0.85f * baseAlpha),
                        radius = laidOut.radius,
                        center = center
                    )
                    drawCircle(
                        color = borderColor.copy(alpha = 0.7f * baseAlpha),
                        radius = laidOut.radius,
                        center = center,
                        style = Stroke(width = 2f / scale)
                    )

                    // Learned patterns carry their confidence as a quiet arc.
                    if (node.tier == MemoryTier.LEARNED_PATTERN) {
                        val arcRadius = laidOut.radius + 6f
                        drawArc(
                            color = tierAccents.getValue(MemoryTier.LEARNED_PATTERN)
                                .copy(alpha = 0.9f * baseAlpha),
                            startAngle = -90f,
                            sweepAngle = 360f * node.confidence.coerceIn(0f, 1f),
                            useCenter = false,
                            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                            size = Size(arcRadius * 2f, arcRadius * 2f),
                            style = Stroke(width = 4f / scale)
                        )
                    }

                    if (node.id == focusedId || node.id == selectedId) {
                        drawCircle(
                            color = accent.copy(alpha = 0.9f),
                            radius = laidOut.radius + 10f,
                            center = center,
                            style = Stroke(width = 3f / scale)
                        )
                    }

                    // Labels only once zoomed in enough to stay readable.
                    if (scale >= 0.38f) {
                        val labelText = node.label.let {
                            if (it.length > 18) it.take(17) + "…" else it
                        }
                        val label = textMeasurer.measure(
                            text = labelText,
                            style = TextStyle(
                                fontSize = 26.sp,
                                color = textPrimary.copy(alpha = baseAlpha)
                            )
                        )
                        drawText(
                            label,
                            color = textPrimary.copy(alpha = baseAlpha),
                            topLeft = Offset(
                                x = center.x - label.size.width / 2f,
                                y = center.y + laidOut.radius + 10f
                            )
                        )
                    }
                }
            }
        }

        if (visible.truncated) {
            Surface(
                color = AppTheme.colors.surfaceRaised,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = "Showing strongest ${visible.nodes.size} of ${visible.totalCount} — " +
                        "refine search or filters",
                    style = MonoMedium,
                    color = textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (focusedId != null) {
            TextButton(
                onClick = { focusedId = null },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text("Clear focus", color = accent, style = MaterialTheme.typography.labelLarge)
            }
        }

        Text(
            text = "Drag to pan · pinch to zoom · tap a memory for details",
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.textTertiary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        )
    }

    // ── Node detail ─────────────────────────────────────────────────────
    val selectedNode = selectedId?.let { id -> visible.nodes.firstOrNull { it.id == id } }
    if (selectedNode != null) {
        GraphNodeDetailSheet(
            node = selectedNode,
            edges = GraphRenderModel.edgesOf(visible, selectedNode.id),
            nodeLabels = visible.nodes.associate { it.id to graphDisplayLabel(it) },
            viewModel = viewModel,
            onDismiss = { selectedId = null },
            onSelectNode = { otherId ->
                focusedId = otherId
                selectedId = otherId
            },
            onPromote = {
                viewModel.promotePattern(selectedNode.id)
                selectedId = null
            },
            onSaveEdit = { label, summary ->
                viewModel.updateKnowledgeNode(selectedNode.id, label, summary)
                selectedId = null
            },
            onDelete = {
                viewModel.deleteKnowledgeNode(selectedNode.id)
                if (focusedId == selectedNode.id) focusedId = null
                selectedId = null
            }
        )
    }
}
