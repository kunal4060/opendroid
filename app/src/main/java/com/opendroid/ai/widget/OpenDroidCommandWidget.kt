package com.opendroid.ai.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.Button
import com.opendroid.ai.core.launch.AgentCommandRunner
import com.opendroid.ai.core.launch.ExternalIntents
import com.opendroid.ai.core.intent.ExternalLaunch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Action parameter carrying the routine id for [RunRoutineActionCallback]. */
val RoutineIdParam = ActionParameters.Key<String>("opendroid_routine_id")

/**
 * The OpenDroid home-screen widget (C1).
 *
 * Two responsive sizes of one idea — a command bar into the agent:
 * - compact: title + live routing label + Voice / New chat buttons;
 * - large: adds the last executed task, the next active routine with a
 *   working **Run** button, and the morning-briefing row.
 *
 * Everything it renders comes from the Glance Preferences state that
 * [WidgetStateSync] distils out of the app's repositories — this class
 * contains no sample text of its own beyond genuine empty states, and every
 * tap is a real [ExternalLaunch] intent (or, for Run, a callback into the
 * real routine engine), so the widget can never show or do anything the app
 * itself wouldn't.
 */
class OpenDroidCommandWidget : GlanceAppWidget() {

    override val sizeMode = androidx.glance.appwidget.SizeMode.Responsive(
        setOf(COMPACT_SIZE, LARGE_SIZE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val appContext = LocalContext.current
            val size = LocalSize.current
            val routingLabel = prefs[OpenDroidWidgetKeys.ROUTING_LABEL]
            val lastTaskText = prefs[OpenDroidWidgetKeys.LAST_TASK_TEXT]

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.widgetBackground)
                    .cornerRadius(24.dp)
                    .padding(12.dp)
                    .clickable(
                        actionStartActivity(
                            ExternalIntents.intent(appContext, ExternalLaunch.OpenChat)
                        )
                    )
            ) {
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    // Header: name + the live routing state (A8's status,
                    // written by WidgetStateSync — never a static string).
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "OpenDroid",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        if (!routingLabel.isNullOrBlank()) {
                            Text(
                                text = routingLabel,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    fontSize = 11.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    if (size.height >= LARGE_MIN_HEIGHT) {
                        Spacer(modifier = GlanceModifier.height(6.dp))
                        Text(
                            text = lastTaskText ?: "No tasks run yet",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 12.sp
                            ),
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = GlanceModifier.height(8.dp))

                    // Command bar: the two one-tap entries into the agent.
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Button(
                            text = "Voice",
                            onClick = actionStartActivity(
                                ExternalIntents.intent(appContext, ExternalLaunch.VoiceCommand)
                            )
                        )
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        Button(
                            text = "New chat",
                            onClick = actionStartActivity(
                                ExternalIntents.intent(appContext, ExternalLaunch.NewChat)
                            )
                        )
                        if (size.height >= LARGE_MIN_HEIGHT) {
                            Spacer(modifier = GlanceModifier.width(8.dp))
                            Button(
                                text = "Brief me",
                                onClick = actionStartActivity(
                                    ExternalIntents.intent(
                                        appContext,
                                        ExternalLaunch.MorningBriefing
                                    )
                                )
                            )
                        }
                    }

                    if (size.height >= LARGE_MIN_HEIGHT) {
                        RoutineSection(prefs, appContext)
                        BriefingSection(prefs)
                    }
                }
            }
        }
    }

    @androidx.glance.GlanceComposable
    private fun RoutineSection(prefs: Preferences, appContext: Context) {
        val routineId = prefs[OpenDroidWidgetKeys.NEXT_ROUTINE_ID]
        val routineName = prefs[OpenDroidWidgetKeys.NEXT_ROUTINE_NAME]
        Spacer(modifier = GlanceModifier.height(10.dp))
        if (routineId != null && routineName != null) {
            val trigger = prefs[OpenDroidWidgetKeys.NEXT_ROUTINE_TRIGGER]
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .clickable(
                        actionStartActivity(
                            ExternalIntents.intent(appContext, ExternalLaunch.OpenRoutines)
                        )
                    )
            ) {
                Text(
                    text = "Next routine",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (trigger.isNullOrBlank()) routineName
                               else "$routineName · $trigger",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 13.sp
                        ),
                        maxLines = 1
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Button(
                        text = "Run",
                        onClick = actionRunCallback<RunRoutineActionCallback>(
                            actionParametersOf(RoutineIdParam to routineId)
                        )
                    )
                }
            }
        } else {
            Text(
                text = "No active routines yet — approve one in the app",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
        }
    }

    @androidx.glance.GlanceComposable
    private fun BriefingSection(prefs: Preferences) {
        val lastBriefingAt = prefs[OpenDroidWidgetKeys.LAST_BRIEFING_AT]
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = if (lastBriefingAt != null) {
                "Last briefing · ${formatTimestamp(lastBriefingAt)}"
            } else {
                "No briefing yet today"
            },
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp
            ),
            maxLines = 1
        )
    }

    private fun formatTimestamp(millis: Long): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(millis))

    companion object {
        private val COMPACT_SIZE = DpSize(180.dp, 84.dp)
        private val LARGE_SIZE = DpSize(280.dp, 188.dp)
        private val LARGE_MIN_HEIGHT = 150.dp
    }
}

/** Hilt access for the widget callback, which the framework instantiates. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OpenDroidWidgetEntryPoint {
    fun agentCommandRunner(): AgentCommandRunner
}

/**
 * The widget's **Run** button: executes the routine in-process through
 * [AgentCommandRunner] — the same engine path as the Routines screen — then
 * reports the outcome and refreshes the widget (the engine's
 * `lastExecutedAt` write also flows back through [WidgetStateSync]).
 *
 * If a routine ever needs more than a quick headless run, the failure text
 * says so honestly instead of pretending success.
 */
class RunRoutineActionCallback : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val routineId = parameters[RoutineIdParam]?.takeIf { it.isNotBlank() } ?: return
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            OpenDroidWidgetEntryPoint::class.java
        )
        val result = runCatching { entryPoint.agentCommandRunner().runRoutine(routineId) }
        val message = result.fold(
            onSuccess = { actionResult ->
                if (actionResult.success) "Routine finished"
                else actionResult.error ?: "Routine could not run"
            },
            onFailure = { "Routine could not run" }
        )
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        OpenDroidCommandWidget().update(context, glanceId)
    }
}
