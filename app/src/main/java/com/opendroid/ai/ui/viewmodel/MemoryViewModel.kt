package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agent.PlanManager
import com.opendroid.ai.core.memory.MemoryManager
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.MemoryRepository
import com.opendroid.ai.core.memory.graph.KnowledgeCategory
import com.opendroid.ai.core.memory.graph.KnowledgeNode
import com.opendroid.ai.core.memory.graph.MemoryOrigin
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.core.memory.graph.OriginKind
import com.opendroid.ai.core.memory.graph.PersonalKnowledgeGraph
import com.opendroid.ai.core.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryManager: MemoryManager,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val planManager: PlanManager,
    private val appLockManager: AppLockManager
) : ViewModel() {

    val workingMemory = memoryManager.workingMemory
    val personalGrowthEngine = memoryManager.personalGrowthEngine

    val knowledgeGraph: StateFlow<PersonalKnowledgeGraph> = personalGrowthEngine.graphFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PersonalKnowledgeGraph()
        )

    val memoriesList: StateFlow<List<Memory>> = memoryRepository.allMemories
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activePlan: StateFlow<Plan?> = planManager.currentPlan

    val conversationHistory: StateFlow<List<ChatMessage>> = conversationRepository.conversations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val macrosList: StateFlow<List<Macro>> = memoryRepository.allMacros
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun storeFact(key: String, value: String, type: MemoryType = MemoryType.SEMANTIC) {
        viewModelScope.launch {
            memoryRepository.saveMemory(
                Memory(
                    key = key,
                    value = value,
                    type = type,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun recordExplicitKnowledge(
        label: String,
        summary: String,
        category: KnowledgeCategory = KnowledgeCategory.NOTE_FACT
    ) {
        viewModelScope.launch {
            personalGrowthEngine.recordExplicitMemory(
                label = label,
                summary = summary,
                category = category,
                // This path is the Memory screen's own add form — the user
                // typed it in directly, which provenance must say.
                origin = MemoryOrigin(
                    kind = OriginKind.USER_ENTERED,
                    detail = "Memory screen",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun recordSensitiveData(key: String, secret: String, label: String) {
        viewModelScope.launch {
            personalGrowthEngine.recordSensitiveSecret(
                key = key,
                secretValue = secret,
                label = label,
                origin = MemoryOrigin(
                    kind = OriginKind.USER_ENTERED,
                    detail = "Memory screen · Level 4 encrypted secret",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun promotePattern(nodeId: String) {
        viewModelScope.launch {
            personalGrowthEngine.promotePatternToExplicit(nodeId)
        }
    }

    fun deleteKnowledgeNode(nodeId: String) {
        viewModelScope.launch {
            personalGrowthEngine.deleteNode(nodeId)
        }
    }

    // ── Graph visualiser (C5) ──────────────────────────────────────────

    /** True when an app PIN exists — the graph's sensitive-reveal fallback gate. */
    val appLockHasPin: StateFlow<Boolean> = appLockManager.hasPin

    /** Label/summary edit from the graph detail view; writes back to the KG. */
    fun updateKnowledgeNode(nodeId: String, label: String, summary: String) {
        viewModelScope.launch {
            personalGrowthEngine.updateNodeContent(nodeId, label, summary)
        }
    }

    /**
     * Reads a Level-4 secret from the Keystore-backed store. Callers must
     * have authenticated the user first (biometric/device credential or the
     * app PIN) — this is the raw value the graph detail reveals after its
     * gate succeeds, and it is never shown anywhere before that point.
     */
    fun sensitiveSecret(key: String): String? = personalGrowthEngine.getSensitiveSecret(key)

    /** Verifies an app-PIN entry for the sensitive-reveal fallback path. */
    fun verifyAppLockPin(pin: String): AppLockManager.PinVerification =
        appLockManager.verifyPin(pin)

    fun clearMemoryTier(tier: MemoryTier) {
        viewModelScope.launch {
            personalGrowthEngine.clearTier(tier)
        }
    }

    /**
     * Wipes the whole knowledge graph — all four tiers, including the
     * Keystore-backed sensitive store. The Growth Graph tab displays all
     * four tiers, so its wipe must clear all of them (it previously cleared
     * only LEARNED_PATTERN while the user believed the graph was wiped).
     */
    fun clearAllGraphTiers() {
        viewModelScope.launch {
            MemoryTier.values().forEach { personalGrowthEngine.clearTier(it) }
        }
    }

    fun deleteMemory(key: String) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(key)
        }
    }

    fun deleteMacro(id: String) {
        viewModelScope.launch {
            memoryRepository.deleteMacro(id)
        }
    }

    fun clearMemories(type: MemoryType) {
        viewModelScope.launch {
            memoryManager.clearMemory(type)
        }
    }
}
