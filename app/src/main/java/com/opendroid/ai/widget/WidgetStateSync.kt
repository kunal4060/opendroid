package com.opendroid.ai.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.opendroid.ai.core.llm.RoutingStatusRepository
import com.opendroid.ai.core.routine.HabitRoutineEngine
import com.opendroid.ai.core.widget.WidgetEventRecorder
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the home-screen widget and the dynamic shortcuts fed with real data.
 *
 * Started once from [com.opendroid.ai.OpenDroidApp] (same pattern as the A5
 * app-lock manager). It observes the app's own sources of truth —
 * [HabitRoutineEngine.activeRoutinesFlow] for routines, [TaskHistoryDao]
 * for the last executed step, [RoutingStatusRepository] for the routing
 * badge, [WidgetEventRecorder] for briefing stamps, [MacroDao] for shortcut
 * fill — and, whenever the distilled [WidgetContent] changes, writes it into
 * the widget's Glance state and asks the widget to re-render.
 *
 * This is the anti-staleness path the C1 checklist demands: a routine run
 * updates `lastExecutedAt` and the routines flow, a briefing stamps the
 * ledger, a failover flips the routing flow — each one lands here and
 * refreshes the widget within a debounce window. The widget's own
 * `updatePeriodMillis` is only a backstop.
 */
@Singleton
class WidgetStateSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitRoutineEngine: HabitRoutineEngine,
    private val taskHistoryDao: TaskHistoryDao,
    private val routingStatusRepository: RoutingStatusRepository,
    private val macroDao: MacroDao,
    private val dynamicShortcutPublisher: DynamicShortcutPublisher
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class Snapshot(
        val content: WidgetContent,
        val routines: List<com.opendroid.ai.data.models.HabitRoutine>,
        val macros: List<com.opendroid.ai.data.db.entities.MacroEntity>
    )

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(
                habitRoutineEngine.activeRoutinesFlow,
                taskHistoryDao.getTaskHistoryFlow(),
                routingStatusRepository.status,
                WidgetEventRecorder.lastBriefingAt(context),
                macroDao.getAllMacrosFlow()
            ) { routines, history, routing, lastBriefingAt, macros ->
                Snapshot(
                    content = WidgetContentMapper.content(
                        activeRoutines = routines,
                        latestTask = history.firstOrNull(),
                        lastBriefingAt = lastBriefingAt,
                        routingStatus = routing
                    ),
                    routines = routines,
                    macros = macros
                )
            }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { snapshot ->
                    runCatching { writeWidgetState(snapshot.content) }
                        .onFailure { Log.w(TAG, "Widget state write failed: ${it.message}") }
                    runCatching { dynamicShortcutPublisher.publish(snapshot.routines, snapshot.macros) }
                        .onFailure { Log.w(TAG, "Dynamic shortcut publish failed: ${it.message}") }
                }
        }
    }

    private suspend fun writeWidgetState(content: WidgetContent) {
        val manager = GlanceAppWidgetManager(context)
        val glanceIds = manager.getGlanceIds(OpenDroidCommandWidget::class.java)
        if (glanceIds.isEmpty()) return
        val widget = OpenDroidCommandWidget()
        glanceIds.forEach { glanceId ->
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs.applyContent(content)
            }
            widget.update(context, glanceId)
        }
    }

    /** Writes [content] into the widget state; null values remove their key. */
    private fun MutablePreferences.applyContent(content: WidgetContent) {
        fun <T> setOrRemove(key: Preferences.Key<T>, value: T?) {
            if (value != null) this[key] = value else this.remove(key)
        }
        setOrRemove(OpenDroidWidgetKeys.NEXT_ROUTINE_ID, content.nextRoutineId)
        setOrRemove(OpenDroidWidgetKeys.NEXT_ROUTINE_NAME, content.nextRoutineName)
        setOrRemove(OpenDroidWidgetKeys.NEXT_ROUTINE_TRIGGER, content.nextRoutineTrigger)
        setOrRemove(OpenDroidWidgetKeys.LAST_TASK_TEXT, content.lastTaskText)
        setOrRemove(OpenDroidWidgetKeys.LAST_BRIEFING_AT, content.lastBriefingAt)
        setOrRemove(OpenDroidWidgetKeys.ROUTING_LABEL, content.routingLabel.takeIf { it.isNotBlank() })
    }

    private companion object {
        const val TAG = "WidgetStateSync"
        const val DEBOUNCE_MS = 400L
    }
}
