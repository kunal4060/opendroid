package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.core.memory.AuditOutcome
import com.opendroid.ai.core.memory.MacroRecording
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Filter state for the audit-log viewer (B4). A null outcome/actionType means "all". */
data class AuditFilter(
    val outcome: AuditOutcome? = null,
    val actionType: String? = null,
    val query: String = ""
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val taskHistoryDao: TaskHistoryDao,
    private val unknownActionDao: UnknownActionDao,
    private val macroDao: MacroDao
) : ViewModel() {

    val taskHistory: StateFlow<List<TaskHistoryEntity>> = taskHistoryDao.getTaskHistoryFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val unknownActions: StateFlow<List<UnknownActionEntity>> = unknownActionDao.getAllUnknownActions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // ---- Audit log (B4) ----

    private val _auditFilter = MutableStateFlow(AuditFilter())
    val auditFilter: StateFlow<AuditFilter> = _auditFilter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val auditEntries: StateFlow<List<TaskHistoryEntity>> = _auditFilter
        .flatMapLatest { filter ->
            val trimmedQuery = filter.query.trim()
            taskHistoryDao.getFilteredHistoryFlow(
                outcome = filter.outcome?.name ?: "ALL",
                actionType = filter.actionType,
                searchPattern = if (trimmedQuery.isEmpty()) null else "%$trimmedQuery%"
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val auditActionTypes: StateFlow<List<String>> = taskHistoryDao.getDistinctActionTypesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val auditTotalCount: StateFlow<Int> = taskHistoryDao.getTotalCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val auditSuccessCount: StateFlow<Int> = taskHistoryDao.getSuccessCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val auditFailureCount: StateFlow<Int> = taskHistoryDao.getFailureCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val auditDeniedCount: StateFlow<Int> = taskHistoryDao.getDeniedCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setAuditOutcome(outcome: AuditOutcome?) {
        _auditFilter.update { it.copy(outcome = outcome) }
    }

    fun setAuditActionType(actionType: String?) {
        _auditFilter.update { it.copy(actionType = actionType) }
    }

    fun setAuditQuery(query: String) {
        _auditFilter.update { it.copy(query = query) }
    }

    /** Loads the full stored step list for [planId] for the audit detail sheet. */
    fun loadPlanEntries(planId: String, onResult: (List<TaskHistoryEntity>) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(taskHistoryDao.getTaskHistoryForPlan(planId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onResult(emptyList())
            }
        }
    }

    fun clearTaskHistory() {
        viewModelScope.launch {
            taskHistoryDao.clearAll()
        }
    }

    fun clearUnknownActions() {
        viewModelScope.launch {
            unknownActionDao.clearAll()
        }
    }

    /** Saves only a fully successful, explicitly selected task as a manual macro. */
    fun saveCompletedTaskAsMacro(
        planId: String,
        name: String,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val trimmedName = name.trim()
                if (trimmedName.isEmpty()) {
                    onResult("Macro name cannot be empty.")
                    return@launch
                }

                val history = taskHistoryDao.getTaskHistoryForPlan(planId)
                val stepsJson = MacroRecording.serializeCompletedTask(history)
                if (stepsJson == null) {
                    onResult("Only fully successful task history can be saved as a macro.")
                    return@launch
                }

                macroDao.insertMacro(
                    MacroEntity(
                        id = UUID.randomUUID().toString(),
                        name = trimmedName,
                        trigger = "manual",
                        stepsJson = stepsJson,
                        isSystem = false,
                        isEnabled = true
                    )
                )
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onResult("Couldn't save that macro right now.")
            }
        }
    }
}
