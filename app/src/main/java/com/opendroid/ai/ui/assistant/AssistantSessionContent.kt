package com.opendroid.ai.ui.assistant

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.assistant.AssistantSessionController
import com.opendroid.ai.ui.theme.AccentGreenButton
import com.opendroid.ai.ui.theme.AccentNeonGreen
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary

/**
 * C3 — the compact UI hosted inside the assistant session window.
 *
 * Deliberately a thin shell over [AssistantSessionController]: it renders
 * the shared [AgentState] (so a plan proposed here looks and behaves like
 * a plan proposed in chat, with working Approve/Reject), shows the live
 * dictation, and offers a typed fallback for noisy rooms. All execution
 * happens in the agent loop behind the controller.
 */
@Composable
fun AssistantSessionContent(
    controller: AssistantSessionController,
    onClose: () -> Unit
) {
    val statusText by controller.statusText.collectAsState()
    val partialTranscript by controller.partialTranscript.collectAsState()
    val lastHeardCommand by controller.lastHeardCommand.collectAsState()
    val assistSource by controller.assistSourcePackage.collectAsState()
    val agentState by controller.agentState.collectAsState()

    var typedCommand by remember { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "OPENDROID ASSISTANT",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = TextPrimary
            )
            if (assistSource != null) {
                Text(
                    text = "Opened from $assistSource",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = statusText,
                fontSize = 14.sp,
                color = AccentNeonGreen
            )

            if (partialTranscript.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = partialTranscript,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
            }

            val heard = lastHeardCommand
            if (heard != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "You said: $heard",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            when (val state = agentState) {
                is AgentState.Thinking -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Thinking…", fontSize = 13.sp, color = TextSecondary)
                }
                is AgentState.PlanProposed -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = state.plan.goal,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    state.plan.steps.forEach { step ->
                        Text(
                            text = "• ${step.description}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { controller.approvePlan() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreenButton)
                        ) {
                            Text("Approve")
                        }
                        OutlinedButton(onClick = { controller.rejectPlan() }) {
                            Text("Reject", color = AccentRed)
                        }
                    }
                    Text(
                        text = "Or just say \"yes\" or \"no\".",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                is AgentState.ExecutingPlan -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Working: ${state.currentStepDesc}",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
                is AgentState.Speaking -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(state.text, fontSize = 13.sp, color = TextPrimary)
                }
                is AgentState.Error -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(state.message, fontSize = 13.sp, color = AccentRed)
                }
                else -> Unit // Idle / Listening render through the status line alone.
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = typedCommand,
                    onValueChange = { typedCommand = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Or type a command…") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (typedCommand.isNotBlank()) {
                            controller.submitTypedCommand(typedCommand)
                            typedCommand = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreenButton)
                ) {
                    Text("Send")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClose) {
                    Text("Close", color = TextSecondary)
                }
            }
        }
    }
}
