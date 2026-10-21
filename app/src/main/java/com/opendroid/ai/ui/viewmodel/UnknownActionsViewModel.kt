package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.ActionDefinition
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.learning.LearnedActionMapping
import com.opendroid.ai.core.learning.LearnedActionStore
import com.opendroid.ai.core.learning.UnknownActionGroup
import com.opendroid.ai.core.learning.UnknownActionGrouping
import com.opendroid.ai.core.learning.UnknownActionReviewState
import com.opendroid.ai.core.learning.normalizeLearnedActionKey
import com.opendroid.ai.core.learning.reviewState
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.models.PlanStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/** One step of a macro being composed in the teach editor. */
data class MacroStepDraft(
    val action: String,
    val params: Map<String, String> = emptyMap()
)

/** A review-queue group joined with its persisted review state. */
data class UnknownActionGroupUi(
    val group: UnknownActionGroup,
    val state: UnknownActionReviewState,
    val mapping: LearnedActionMapping?
)

/**
 * B5 — Action Learning. Backs the review screen that closes the
 * unknown-action loop: rows come from the real `unknown_actions` table
 * (written by ActionDispatcher / PlanValidator / ReEvaluationEngine), review
 * state lives in [LearnedActionStore], and "convert to macro" writes a real
 * [MacroEntity] through the existing macro machinery.
 */
@HiltViewModel
class UnknownActionsViewModel @Inject constructor(
    private val unknownActionDao: UnknownActionDao,
    private val macroDao: MacroDao,
    private val learnedActionStore: LearnedActionStore,
    private val actionDispatcher: ActionDispatcher
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    val groups: StateFlow<List<UnknownActionGroupUi>> =
        combine(unknownActionDao.getAllUnknownActions(), learnedActionStore.mappings) { rows, mappings ->
            UnknownActionGrouping.group(rows).map { group ->
                val mapping = mappings[group.key]
                UnknownActionGroupUi(
                    group = group,
                    state = mapping.reviewState(),
                    mapping = mapping
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Groups still waiting for a user decision — drives the entry badges. */
    val pendingCount: StateFlow<Int> =
        combine(unknownActionDao.getAllUnknownActions(), learnedActionStore.mappings) { rows, mappings ->
            UnknownActionGrouping.group(rows).count { group -> mappings[group.key].reviewState() == UnknownActionReviewState.PENDING }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    /** Every action the dispatcher can actually run — the only legal teach targets. */
    fun registeredActions(): List<String> =
        actionDispatcher.getAllRegisteredActions().sorted()

    /** Schema (parameter definitions) for a teach target, when it has one. */
    fun actionDefinition(actionName: String): ActionDefinition? =
        ActionSchema.getAction(normalizeLearnedActionKey(actionName))

    fun dismiss(groupKey: String) {
        viewModelScope.launch {
            learnedActionStore.save(
                LearnedActionMapping(
                    attemptedAction = groupKey,
                    kind = LearnedActionMapping.Kind.DISMISSED
                )
            )
        }
    }

    /** Back to PENDING. A converted macro itself stays in Macros (user deletes it there). */
    fun undoReview(groupKey: String) {
        viewModelScope.launch {
            learnedActionStore.remove(groupKey)
        }
    }

    fun saveActionMapping(groupKey: String, targetAction: String, paramTemplate: Map<String, String>) {
        val target = normalizeLearnedActionKey(targetAction)
        if (target !in actionDispatcher.getAllRegisteredActions()) return
        viewModelScope.launch {
            learnedActionStore.save(
                LearnedActionMapping(
                    attemptedAction = groupKey,
                    kind = LearnedActionMapping.Kind.ACTION,
                    targetAction = target,
                    paramTemplate = paramTemplate.filterValues { it.isNotBlank() }
                )
            )
        }
    }

    /**
     * Creates a real macro from the composed steps and teaches the verb to
     * run it. [onResult] receives null on success or a user-readable error.
     */
    fun saveMacroMapping(
        groupKey: String,
        macroName: String,
        steps: List<MacroStepDraft>,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val name = macroName.trim()
                if (name.isEmpty()) {
                    onResult("Macro name cannot be empty.")
                    return@launch
                }
                if (steps.isEmpty()) {
                    onResult("Add at least one step to the macro.")
                    return@launch
                }
                val registered = actionDispatcher.getAllRegisteredActions().toSet()
                val badStep = steps.firstOrNull {
                    normalizeLearnedActionKey(it.action) !in registered
                }
                if (badStep != null) {
                    onResult("'${badStep.action}' is not a registered action and cannot be a macro step.")
                    return@launch
                }
                if (macroDao.getMacroByName(name) != null) {
                    onResult("A macro named '$name' already exists. Pick another name.")
                    return@launch
                }

                val planSteps = steps.mapIndexed { index, draft ->
                    PlanStep(
                        stepId = "learned-step-${index + 1}",
                        order = index + 1,
                        description = "Taught step ${index + 1}: ${normalizeLearnedActionKey(draft.action)}",
                        action = normalizeLearnedActionKey(draft.action),
                        params = draft.params.filterValues { it.isNotBlank() }
                    )
                }
                macroDao.insertMacro(
                    MacroEntity(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        trigger = "manual",
                        stepsJson = json.encodeToString(planSteps),
                        isSystem = false,
                        isEnabled = true
                    )
                )
                learnedActionStore.save(
                    LearnedActionMapping(
                        attemptedAction = groupKey,
                        kind = LearnedActionMapping.Kind.MACRO,
                        macroName = name
                    )
                )
                onResult(null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult("Couldn't save that macro right now.")
            }
        }
    }
}
