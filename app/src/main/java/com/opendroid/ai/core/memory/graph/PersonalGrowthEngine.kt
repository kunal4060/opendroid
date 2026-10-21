package com.opendroid.ai.core.memory.graph

import android.util.Log
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.core.security.SensitiveMemoryStore
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Personal Growth Engine maintains a structured Personal Knowledge Graph
 * across 4 security and retention tiers:
 * 1. Temporary (active task/session memory)
 * 2. Long-Term (explicitly saved facts, notes, projects, preferences)
 * 3. Learned Patterns (inferred behaviors with confidence scoring)
 * 4. Sensitive Data (encrypted at rest via AndroidKeyStore AES-256-GCM)
 */
@Singleton
class PersonalGrowthEngine @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val sensitiveMemoryStore: SensitiveMemoryStore,
    private val workingMemory: WorkingMemory,
    private val notificationIntelligence: NotificationIntelligence
) {
    companion object {
        private const val TAG = "PersonalGrowthEngine"
        private const val PKG_NODE_PREFIX = "pkg_node_"
        private const val PKG_RELATION_KEY = "pkg_relations_index"
        private const val PROP_CATEGORY_COUNTS = "categoryCounts"
        private const val PROP_CHANNEL_COUNTS = "channelCounts"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }

    /**
     * Serializes every read-modify-write of graph state (pattern/chatfact
     * recorders, relation updates, node deletes, tier clears). Without it,
     * parallel callers (e.g. concurrent plan steps logging executions) can
     * both read count *n* and both write *n+1*, silently losing observations.
     */
    private val graphMutex = Mutex()

    /**
     * Observe the Personal Knowledge Graph nodes as a Flow.
     */
    val graphFlow: Flow<PersonalKnowledgeGraph> = memoryRepository.allMemories.map { memories ->
        val nodes = mutableMapOf<String, KnowledgeNode>()
        var relations: List<KnowledgeRelation> = emptyList()
        for (memory in memories) {
            if (memory.key == PKG_RELATION_KEY) {
                relations = try {
                    json.decodeFromString<List<KnowledgeRelation>>(memory.value)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode knowledge relations index")
                    emptyList()
                }
            } else if (memory.key.startsWith(PKG_NODE_PREFIX)) {
                try {
                    val node = json.decodeFromString<KnowledgeNode>(memory.value)
                    nodes[node.id] = node
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode knowledge node: ${memory.key}")
                }
            }
        }
        // Sensitive records written straight to the store (e.g. by a backup
        // restore) have no node row; synthesize metadata nodes exactly like
        // getSnapshot() so the Memory screen can show/reveal/delete them too.
        addSynthesizedSensitiveNodes(nodes)
        PersonalKnowledgeGraph(nodes = nodes, relations = relations)
    }

    /**
     * Load the current snapshot of the Personal Knowledge Graph.
     */
    suspend fun getSnapshot(): PersonalKnowledgeGraph {
        val memories = memoryRepository.getMemoriesByKeyPrefix(PKG_NODE_PREFIX)
        val nodes = mutableMapOf<String, KnowledgeNode>()
        for (memory in memories) {
            try {
                val node = json.decodeFromString<KnowledgeNode>(memory.value)
                nodes[node.id] = node
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode knowledge node: ${memory.key}")
            }
        }

        // Add Sensitive Data metadata nodes (without leaking secrets)
        addSynthesizedSensitiveNodes(nodes)

        return PersonalKnowledgeGraph(nodes = nodes, relations = loadRelations())
    }

    /**
     * Adds a metadata node for every key in the sensitive store that has no
     * persisted node row, so directly-written secrets (backup restores) are
     * visible — and manageable — everywhere the graph is shown. Shared by
     * [getSnapshot] and [graphFlow] so the two never disagree.
     */
    private fun addSynthesizedSensitiveNodes(nodes: MutableMap<String, KnowledgeNode>) {
        for (key in sensitiveMemoryStore.listKeys()) {
            val sensitiveNodeId = "sensitive_$key"
            if (!nodes.containsKey(sensitiveNodeId)) {
                nodes[sensitiveNodeId] = KnowledgeNode(
                    id = sensitiveNodeId,
                    label = key,
                    category = KnowledgeCategory.USER_PREFERENCE,
                    tier = MemoryTier.SENSITIVE,
                    summary = "Hardware-encrypted sensitive record ($key)",
                    confidence = 1.0f,
                    properties = mapOf("encrypted" to "true")
                )
            }
        }
    }

    // ── RELATIONS INDEX (persisted graph edges) ────────────────────────

    private suspend fun loadRelations(): List<KnowledgeRelation> {
        val relationsMemory = memoryRepository.getMemoriesByKeyPrefix(PKG_RELATION_KEY)
            .firstOrNull { it.key == PKG_RELATION_KEY } ?: return emptyList()
        return try {
            json.decodeFromString<List<KnowledgeRelation>>(relationsMemory.value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode knowledge relations index")
            emptyList()
        }
    }

    private suspend fun saveRelations(relations: List<KnowledgeRelation>) {
        if (relations.isEmpty()) {
            memoryRepository.deleteMemory(PKG_RELATION_KEY)
        } else {
            memoryRepository.saveMemory(
                Memory(
                    key = PKG_RELATION_KEY,
                    value = json.encodeToString(relations),
                    type = MemoryType.SEMANTIC,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Adds (or replaces, by source/target/type triple) a relation and
     * persists the relations index. This is the write path the graph UI and
     * recorders use — previously relations existed only as an in-memory
     * `copy()` on a snapshot and were never stored at all.
     *
     * Caller must hold [graphMutex] (all public entry points do).
     */
    private suspend fun addRelationLocked(relation: KnowledgeRelation) {
        val merged = PersonalKnowledgeGraph(relations = loadRelations())
            .addRelation(relation).relations
        saveRelations(merged)
    }

    /** Persists a relation between two nodes; see [addRelationLocked]. */
    suspend fun addRelation(relation: KnowledgeRelation) {
        graphMutex.withLock { addRelationLocked(relation) }
    }

    /**
     * Drops every relation touching any of [nodeIds] and re-persists the
     * index, so deleted nodes never leave orphan edges behind for a later
     * node with the same deterministic id to inherit.
     *
     * Caller must hold [graphMutex].
     */
    private suspend fun removeRelationsTouchingLocked(nodeIds: Set<String>) {
        if (nodeIds.isEmpty()) return
        val relations = loadRelations()
        val filtered = relations.filter { it.sourceId !in nodeIds && it.targetId !in nodeIds }
        if (filtered.size != relations.size) {
            saveRelations(filtered)
        }
    }

    // ── LEVEL 1: TEMPORARY MEMORY (Current Task Context) ───────────────

    /**
     * Records one piece of current-task context as a TEMPORARY-tier node.
     * (Previously this wrote into [WorkingMemory.location], whose getter
     * overwrites the field with the device location on every read — the
     * context was silently lost, successive entries overwrote each other,
     * and no TEMPORARY node ever existed.) One node per key, so successive
     * keys accumulate and re-recording a key updates it in place.
     */
    suspend fun recordTemporaryTaskContext(key: String, value: String) {
        graphMutex.withLock {
            val nodeId = "temporary_${key.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(40)}"
            val existing = getNodeFromDb(nodeId)
            val now = System.currentTimeMillis()
            saveNodeToDb(
                KnowledgeNode(
                    id = nodeId,
                    label = key,
                    category = KnowledgeCategory.TASK_ROUTINE,
                    tier = MemoryTier.TEMPORARY,
                    summary = value,
                    properties = mapOf("contextKey" to key, "contextValue" to value),
                    confidence = 1.0f,
                    accessCount = (existing?.accessCount ?: 0) + 1,
                    lastUpdated = now,
                    origin = existing?.origin ?: MemoryOrigin(
                        kind = OriginKind.TASK_PATTERN,
                        detail = "Temporary task context",
                        createdAt = now
                    )
                )
            )
        }
    }

    /** All current temporary task-context entries, most recently updated first. */
    suspend fun getTemporaryTaskContexts(): List<KnowledgeNode> {
        return getSnapshot().getNodesByTier(MemoryTier.TEMPORARY)
            .sortedByDescending { it.lastUpdated }
    }

    // ── LEVEL 2: LONG-TERM MEMORY (Explicit User Memories) ─────────────

    suspend fun recordExplicitMemory(
        label: String,
        summary: String,
        category: KnowledgeCategory = KnowledgeCategory.NOTE_FACT,
        properties: Map<String, String> = emptyMap(),
        origin: MemoryOrigin? = null
    ): KnowledgeNode {
        val now = System.currentTimeMillis()
        val sanitizedId = "explicit_${label.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(30)}_$now"
        val node = KnowledgeNode(
            id = sanitizedId,
            label = label,
            category = category,
            tier = MemoryTier.LONG_TERM,
            summary = summary,
            properties = properties,
            confidence = 1.0f,
            accessCount = 1,
            lastUpdated = now,
            // Callers that know a more precise origin (manual UI entry, detected
            // routine, …) pass it in; the default is the honest common case:
            // the user asked for this to be remembered.
            origin = origin ?: MemoryOrigin(kind = OriginKind.USER_EXPLICIT, createdAt = now)
        )
        saveNodeToDb(node)
        return node
    }

    /**
     * Records a fact that [com.opendroid.ai.core.memory.MemoryExtractor] pulled
     * out of the user's chat messages. Unlike [recordExplicitMemory] this uses a
     * deterministic id per fact key, so re-extracting the same fact updates the
     * existing node (preserving its original [MemoryOrigin.createdAt]) instead
     * of piling up duplicates on every chat turn.
     */
    suspend fun recordChatExtractedFact(
        factKey: String,
        factValue: String,
        sessionId: String?,
        sourceMessageTimestamp: Long
    ): KnowledgeNode = graphMutex.withLock {
        val nodeId = "chatfact_${factKey.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(40)}"
        val existing = getNodeFromDb(nodeId)
        val now = System.currentTimeMillis()
        val node = KnowledgeNode(
            id = nodeId,
            label = factKey,
            category = KnowledgeCategory.NOTE_FACT,
            tier = MemoryTier.LONG_TERM,
            summary = factValue,
            properties = mapOf("factKey" to factKey, "source" to "chat_extraction"),
            confidence = 1.0f,
            accessCount = (existing?.accessCount ?: 0) + 1,
            lastUpdated = now,
            origin = existing?.origin ?: MemoryOrigin(
                kind = OriginKind.CHAT_EXTRACTION,
                detail = "Extracted automatically from your chat messages",
                sessionId = sessionId,
                createdAt = if (sourceMessageTimestamp > 0L) sourceMessageTimestamp else now
            )
        )
        saveNodeToDb(node)
        node
    }

    suspend fun recordProject(
        projectName: String,
        description: String,
        status: String = "active"
    ): KnowledgeNode {
        return recordExplicitMemory(
            label = projectName,
            summary = description,
            category = KnowledgeCategory.PROJECT,
            properties = mapOf("status" to status, "type" to "project")
        )
    }

    suspend fun recordUserPreference(
        preferenceKey: String,
        preferenceValue: String
    ): KnowledgeNode {
        return recordExplicitMemory(
            label = preferenceKey,
            summary = preferenceValue,
            category = KnowledgeCategory.USER_PREFERENCE,
            properties = mapOf("preferenceKey" to preferenceKey, "preferenceValue" to preferenceValue)
        )
    }

    // ── LEVEL 3: LEARNED PATTERNS (Behavioral Inferences) ──────────────

    suspend fun recordAppUsage(appName: String, actionCategory: String) {
        graphMutex.withLock { recordAppUsageLocked(appName, actionCategory) }
    }

    private suspend fun recordAppUsageLocked(appName: String, actionCategory: String) {
        val nodeId = "pattern_app_${appName.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1
        val confidence = when {
            count >= 10 -> 0.95f
            count >= 5 -> 0.85f
            count >= 3 -> 0.70f
            else -> 0.50f
        }

        // Per-category counts: one node per app, but usage in different
        // categories is tracked separately so the summary can attribute the
        // modal (most frequent) category its OWN count instead of claiming
        // the cross-category total for whichever category happened last.
        val categoryCounts = parseCountMap(existing?.properties?.get(PROP_CATEGORY_COUNTS))
        if (categoryCounts.isEmpty()) {
            // Node written before per-category tracking: its whole history
            // was attributed to the single stored category — seed from it.
            val legacyCategory = existing?.properties?.get("category")
            val legacyCount = existing?.properties?.get("count")?.toIntOrNull()
            if (legacyCategory != null && legacyCount != null && legacyCount > 0) {
                categoryCounts[legacyCategory] = legacyCount
            }
        }
        categoryCounts[actionCategory] = (categoryCounts[actionCategory] ?: 0) + 1
        val modalCategory = categoryCounts.maxByOrNull { it.value }?.key ?: actionCategory
        val modalCount = categoryCounts[modalCategory] ?: count
        val breakdown = categoryCounts.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} ×${it.value}" }

        val node = KnowledgeNode(
            id = nodeId,
            label = appName,
            category = KnowledgeCategory.APP_PREFERENCE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Preferred app for $modalCategory: $appName (used $modalCount of $count times — $breakdown)",
            properties = mapOf(
                "appName" to appName,
                "category" to modalCategory,
                "count" to count.toString(),
                PROP_CATEGORY_COUNTS to encodeCountMap(categoryCounts)
            ),
            confidence = confidence,
            accessCount = count,
            lastUpdated = System.currentTimeMillis(),
            // First observation stamps the origin; re-observation preserves it
            // so provenance keeps the original "first learned" moment.
            origin = existing?.origin ?: MemoryOrigin(
                kind = OriginKind.APP_USAGE,
                detail = actionCategory,
                createdAt = System.currentTimeMillis()
            )
        )
        saveNodeToDb(node)
    }

    suspend fun recordContactInteraction(contactName: String, channel: String) {
        graphMutex.withLock { recordContactInteractionLocked(contactName, channel) }
    }

    private suspend fun recordContactInteractionLocked(contactName: String, channel: String) {
        val nodeId = "pattern_contact_${contactName.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1
        val confidence = when {
            count >= 10 -> 0.95f
            count >= 5 -> 0.85f
            count >= 3 -> 0.70f
            else -> 0.50f
        }

        // Per-channel counts: the summary reports the modal (most used)
        // channel with ITS count — previously one SMS after ten WhatsApp
        // messages rewrote the summary to "via SMS (11 interactions)" and
        // flipped preferredChannel to SMS.
        val channelCounts = parseCountMap(existing?.properties?.get(PROP_CHANNEL_COUNTS))
        if (channelCounts.isEmpty()) {
            // Node written before per-channel tracking: seed from the single
            // stored channel/count (its attribution was imperfect, but it is
            // the only history there is).
            val legacyChannel = existing?.properties?.get("preferredChannel")
            val legacyCount = existing?.properties?.get("count")?.toIntOrNull()
            if (legacyChannel != null && legacyCount != null && legacyCount > 0) {
                channelCounts[legacyChannel] = legacyCount
            }
        }
        channelCounts[channel] = (channelCounts[channel] ?: 0) + 1
        val modalChannel = channelCounts.maxByOrNull { it.value }?.key ?: channel
        val modalCount = channelCounts[modalChannel] ?: count

        val node = KnowledgeNode(
            id = nodeId,
            label = contactName,
            category = KnowledgeCategory.CONTACT,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Frequent contact: $contactName via $modalChannel ($modalCount of $count interactions)",
            properties = mapOf(
                "contactName" to contactName,
                "preferredChannel" to modalChannel,
                "count" to count.toString(),
                PROP_CHANNEL_COUNTS to encodeCountMap(channelCounts)
            ),
            confidence = confidence,
            accessCount = count,
            lastUpdated = System.currentTimeMillis(),
            origin = existing?.origin ?: MemoryOrigin(
                kind = OriginKind.CONTACT_INTERACTION,
                detail = channel,
                createdAt = System.currentTimeMillis()
            )
        )
        saveNodeToDb(node)
    }

    suspend fun recordTaskOrRoutine(taskTitle: String, timeContext: String, frequencyHint: String = "recurring") {
        graphMutex.withLock { recordTaskOrRoutineLocked(taskTitle, timeContext, frequencyHint) }
    }

    private suspend fun recordTaskOrRoutineLocked(taskTitle: String, timeContext: String, frequencyHint: String) {
        val nodeId = "pattern_routine_${taskTitle.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1
        val confidence = when {
            count >= 5 -> 0.90f
            count >= 2 -> 0.75f
            else -> 0.60f
        }

        val node = KnowledgeNode(
            id = nodeId,
            label = taskTitle,
            category = KnowledgeCategory.TASK_ROUTINE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Routine: $taskTitle at $timeContext ($frequencyHint, triggered $count times)",
            properties = mapOf("time" to timeContext, "frequency" to frequencyHint, "count" to count.toString()),
            confidence = confidence,
            accessCount = count,
            lastUpdated = System.currentTimeMillis(),
            origin = existing?.origin ?: MemoryOrigin(
                kind = OriginKind.TASK_PATTERN,
                detail = "$frequencyHint · $timeContext",
                createdAt = System.currentTimeMillis()
            )
        )
        saveNodeToDb(node)
    }

    suspend fun recordCommonSchedule(eventTitle: String, dayOrTime: String) {
        graphMutex.withLock { recordCommonScheduleLocked(eventTitle, dayOrTime) }
    }

    private suspend fun recordCommonScheduleLocked(eventTitle: String, dayOrTime: String) {
        val nodeId = "pattern_schedule_${eventTitle.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1
        val node = KnowledgeNode(
            id = nodeId,
            label = eventTitle,
            category = KnowledgeCategory.SCHEDULE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Common schedule: $eventTitle on $dayOrTime",
            properties = mapOf("schedule" to dayOrTime, "count" to count.toString()),
            confidence = if (count >= 3) 0.85f else 0.60f,
            accessCount = count,
            lastUpdated = System.currentTimeMillis(),
            origin = existing?.origin ?: MemoryOrigin(
                kind = OriginKind.SCHEDULE_PATTERN,
                detail = dayOrTime,
                createdAt = System.currentTimeMillis()
            )
        )
        saveNodeToDb(node)
    }

    suspend fun recordWebsiteVisit(url: String, domain: String) {
        graphMutex.withLock { recordWebsiteVisitLocked(url, domain) }
    }

    private suspend fun recordWebsiteVisitLocked(url: String, domain: String) {
        val cleanDomain = domain.ifBlank { url.substringAfter("://").substringBefore("/") }
        val nodeId = "pattern_web_${cleanDomain.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1

        val node = KnowledgeNode(
            id = nodeId,
            label = cleanDomain,
            category = KnowledgeCategory.RESOURCE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Frequently visited resource: $cleanDomain (accessed $count times)",
            properties = mapOf("url" to url, "domain" to cleanDomain, "count" to count.toString()),
            confidence = if (count >= 5) 0.90f else 0.65f,
            accessCount = count,
            lastUpdated = System.currentTimeMillis(),
            origin = existing?.origin ?: MemoryOrigin(
                kind = OriginKind.WEBSITE_VISIT,
                detail = cleanDomain,
                createdAt = System.currentTimeMillis()
            )
        )
        saveNodeToDb(node)
    }

    // ── LEVEL 4: SENSITIVE DATA (Encrypted Keystore Storage) ────────────

    suspend fun recordSensitiveSecret(
        key: String,
        secretValue: String,
        label: String,
        origin: MemoryOrigin? = null
    ): Boolean = graphMutex.withLock {
        val success = sensitiveMemoryStore.write(key, secretValue)
        if (success) {
            val nodeId = "sensitive_$key"
            val node = KnowledgeNode(
                id = nodeId,
                label = label.ifBlank { key },
                category = KnowledgeCategory.USER_PREFERENCE,
                tier = MemoryTier.SENSITIVE,
                summary = "Hardware-encrypted sensitive record ($label)",
                confidence = 1.0f,
                properties = mapOf("key" to key, "encrypted" to "true"),
                // Provenance never carries the secret itself — only how the
                // record came to be stored.
                origin = origin ?: MemoryOrigin(
                    kind = OriginKind.USER_EXPLICIT,
                    detail = "Level 4 encrypted secret",
                    createdAt = System.currentTimeMillis()
                )
            )
            saveNodeToDb(node)
        }
        success
    }

    fun getSensitiveSecret(key: String): String? {
        return sensitiveMemoryStore.read(key)
    }

    // ── PROMOTION & MANAGEMENT ─────────────────────────────────────────

    suspend fun promotePatternToExplicit(nodeId: String): Boolean = graphMutex.withLock {
        val existing = getNodeFromDb(nodeId) ?: return@withLock false
        val updated = existing.copy(
            tier = MemoryTier.LONG_TERM,
            confidence = 1.0f,
            lastUpdated = System.currentTimeMillis()
        )
        saveNodeToDb(updated)
        true
    }

    /**
     * Edits a node's label/summary in place (same id, so stored relations
     * keep pointing at it) and re-saves it through the normal node row.
     * Sensitive nodes are refused: their content lives in the
     * Keystore-encrypted store, never in node JSON, so there is nothing
     * plaintext here to edit — a secret is changed by deleting and
     * re-adding the record.
     */
    suspend fun updateNodeContent(nodeId: String, newLabel: String, newSummary: String): Boolean =
        graphMutex.withLock {
            val existing = getNodeFromDb(nodeId) ?: return@withLock false
            if (existing.tier == MemoryTier.SENSITIVE) return@withLock false
            saveNodeToDb(
                existing.copy(
                    label = newLabel,
                    summary = newSummary,
                    lastUpdated = System.currentTimeMillis()
                )
            )
            true
        }

    suspend fun deleteNode(nodeId: String): Boolean = graphMutex.withLock {
        deleteNodeLocked(nodeId)
    }

    /**
     * Deletes a node row (plus its Keystore secret for sensitive nodes) and
     * cascades the relations index, mirroring
     * [PersonalKnowledgeGraph.removeNode]. Caller must hold [graphMutex].
     */
    private suspend fun deleteNodeLocked(nodeId: String): Boolean {
        if (nodeId.startsWith("sensitive_")) {
            val secretKey = nodeId.removePrefix("sensitive_")
            sensitiveMemoryStore.remove(secretKey)
        }
        memoryRepository.deleteMemory("$PKG_NODE_PREFIX$nodeId")
        removeRelationsTouchingLocked(setOf(nodeId))
        return true
    }

    /**
     * Deletes every SENSITIVE-tier node — row, Keystore secret, and any
     * relations touching it. Used by `MemoryManager.clearMemory(SEMANTIC)`:
     * graph node rows are typed SEMANTIC, so a plain type-based wipe would
     * otherwise orphan the actual secrets in the Keystore with no node left
     * to reveal or delete them through.
     */
    suspend fun deleteAllSensitiveNodes() {
        graphMutex.withLock {
            val ids = getSnapshot().getNodesByTier(MemoryTier.SENSITIVE).map { it.id }
            for (id in ids) {
                deleteNodeLocked(id)
            }
        }
    }

    suspend fun clearTier(tier: MemoryTier) {
        graphMutex.withLock { clearTierLocked(tier) }
    }

    private suspend fun clearTierLocked(tier: MemoryTier) {
        when (tier) {
            MemoryTier.TEMPORARY -> {
                workingMemory.clear()
                // Temporary task context also lives in TEMPORARY node rows.
                val tempIds = getSnapshot().getNodesByTier(MemoryTier.TEMPORARY)
                    .map { it.id }.toSet()
                for (id in tempIds) {
                    memoryRepository.deleteMemory("$PKG_NODE_PREFIX$id")
                }
                removeRelationsTouchingLocked(tempIds)
            }
            MemoryTier.SENSITIVE -> {
                // Snapshot the sensitive nodes FIRST: their rows must be
                // deleted too — previously only the store was cleared, so the
                // graph kept listing records whose reveal then failed.
                val sensitiveIds = getSnapshot().getNodesByTier(MemoryTier.SENSITIVE)
                    .map { it.id }.toSet()
                for (id in sensitiveIds) {
                    memoryRepository.deleteMemory("$PKG_NODE_PREFIX$id")
                }
                sensitiveMemoryStore.clearAll()
                removeRelationsTouchingLocked(sensitiveIds)
            }
            else -> {
                val snapshot = getSnapshot()
                val nodesInTier = snapshot.getNodesByTier(tier)
                for (node in nodesInTier) {
                    memoryRepository.deleteMemory("$PKG_NODE_PREFIX${node.id}")
                }
                removeRelationsTouchingLocked(nodesInTier.map { it.id }.toSet())
            }
        }
    }

    // ── SYNTHESIZED CONTEXT FOR LLM PROMPTS ────────────────────────────

    suspend fun generateSynthesizedContext(query: String = ""): String {
        val snapshot = getSnapshot()
        val allLongTerm = snapshot.getNodesByTier(MemoryTier.LONG_TERM)
        val allPatterns = snapshot.getNodesByTier(MemoryTier.LEARNED_PATTERN)
            .filter { it.confidence >= 0.6f }

        // Rank by the caller's query: nodes matching findNodes(query) lead
        // each section, remaining slots fall back to the recency/confidence
        // ordering. (The query used to be ignored entirely, so every prompt
        // received the same ten memories regardless of relevance.)
        val matchedIds = if (query.isNotBlank()) {
            snapshot.findNodes(query).map { it.id }.toSet()
        } else {
            emptySet()
        }
        val longTerm = allLongTerm.sortedWith(
            compareByDescending<KnowledgeNode> { it.id in matchedIds }
                .thenByDescending { it.lastUpdated }
        ).take(10)
        val patterns = allPatterns.sortedWith(
            compareByDescending<KnowledgeNode> { it.id in matchedIds }
                .thenByDescending { it.confidence }
        ).take(8)

        val sb = StringBuilder()
        if (longTerm.isNotEmpty()) {
            sb.append("[Long-Term Explicit Memory]\n")
            longTerm.forEach { node ->
                sb.append("• ${node.label}: ${node.summary}\n")
            }
        }

        if (patterns.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[Learned Behavioral Patterns (Inferred)]\n")
            patterns.forEach { node ->
                val confPercent = (node.confidence * 100).toInt()
                sb.append("• ${node.summary} (${confPercent}% confidence)\n")
            }
        }

        return sb.toString().trim()
    }

    /**
     * Decodes a `"label=count;label=count"` property (per-category /
     * per-channel tallies on pattern nodes) into an insertion-ordered map.
     * Malformed entries are skipped rather than failing the recorder.
     */
    private fun parseCountMap(raw: String?): LinkedHashMap<String, Int> {
        val result = LinkedHashMap<String, Int>()
        if (raw.isNullOrBlank()) return result
        for (entry in raw.split(";")) {
            val label = entry.substringBeforeLast("=", "").trim()
            val count = entry.substringAfterLast("=", "").trim().toIntOrNull()
            if (label.isNotEmpty() && count != null) {
                result[label] = (result[label] ?: 0) + count
            }
        }
        return result
    }

    private fun encodeCountMap(counts: Map<String, Int>): String =
        counts.entries.joinToString(";") { "${it.key}=${it.value}" }

    private suspend fun saveNodeToDb(node: KnowledgeNode) {
        val key = "$PKG_NODE_PREFIX${node.id}"
        val value = json.encodeToString(node)
        memoryRepository.saveMemory(
            Memory(
                key = key,
                value = value,
                type = MemoryType.SEMANTIC,
                timestamp = node.lastUpdated
            )
        )
    }

    private suspend fun getNodeFromDb(nodeId: String): KnowledgeNode? {
        val key = "$PKG_NODE_PREFIX$nodeId"
        val memories = memoryRepository.getMemoriesByKeyPrefix(key)
        val memory = memories.firstOrNull { it.key == key } ?: return null
        return try {
            json.decodeFromString<KnowledgeNode>(memory.value)
        } catch (e: Exception) {
            null
        }
    }
}
