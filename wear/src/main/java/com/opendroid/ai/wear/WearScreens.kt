package com.opendroid.ai.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The watch UI (feature D7) — one scrollable column, state-driven:
 *
 *  - Phone unreachable → an honest unavailable screen. No command button,
 *    no approval buttons, nothing that could look like a queued action:
 *    only an explanation and a Refresh chip.
 *  - Phone reachable → approval card first when the phone published one
 *    (and it hasn't expired), then live agent status, the voice-command
 *    chip, the in-flight/last command state, and the recent-task list —
 *    all rendered from the phone's published snapshot, never local guesses.
 */
@Composable
fun WearApp(repository: WearRepository, onVoiceCommand: () -> Unit) {
    val reachable by repository.phoneReachable.collectAsState()
    val status by repository.status.collectAsState()
    val approval by repository.pendingApproval.collectAsState()
    val commandState by repository.commandState.collectAsState()
    val decisions by repository.sentDecisions.collectAsState()
    val listState = rememberScalingLazyListState()

    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                if (!reachable) {
                    item {
                        Text(
                            text = "Phone not reachable",
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text(
                            text = "Commands and approvals are unavailable until your phone is in range. Nothing is queued.",
                            style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                    item {
                        Chip(
                            label = { Text("Refresh") },
                            onClick = { repository.refreshConnection() },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                    return@ScalingLazyColumn
                }

                // ── Pending plan approval ──────────────────────────────
                val liveApproval = approval?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
                if (liveApproval != null) {
                    item {
                        Text(
                            text = "Approval needed",
                            style = MaterialTheme.typography.title3,
                            color = MaterialTheme.colors.primary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text(
                            text = liveApproval.goal,
                            style = MaterialTheme.typography.body1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    items(liveApproval.steps) { step ->
                        Text(
                            text = "• $step",
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    val sentDecision = decisions[liveApproval.planId]
                    if (sentDecision != null) {
                        item {
                            Text(
                                text = if (sentDecision) "Approved — sent to phone" else "Denied — sent to phone",
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                    } else {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        repository.sendApprovalDecision(liveApproval.planId, approved = false)
                                    },
                                    colors = ButtonDefaults.secondaryButtonColors(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Deny") }
                                Button(
                                    onClick = {
                                        repository.sendApprovalDecision(liveApproval.planId, approved = true)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Approve") }
                            }
                        }
                    }
                } else if (approval != null) {
                    item {
                        Text(
                            text = "Approval expired — use the phone",
                            style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ── Agent status ───────────────────────────────────────
                item {
                    Text(
                        text = "OpenDroid",
                        style = MaterialTheme.typography.title3,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
                if (status == null) {
                    item {
                        Text(
                            text = "Waiting for phone status…",
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    item {
                        Text(
                            text = status!!.agentState,
                            style = MaterialTheme.typography.body1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text(
                            text = status!!.routingLabel,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ── Voice command ──────────────────────────────────────
                item {
                    Chip(
                        label = { Text("Voice command") },
                        secondaryLabel = { Text("Dictate or type") },
                        onClick = onVoiceCommand,
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                when (val state = commandState) {
                    WearCommandState.Idle -> Unit
                    is WearCommandState.Sending -> {
                        item { CircularProgressIndicator() }
                        item {
                            Text(
                                text = "Sent: ${state.text}",
                                style = MaterialTheme.typography.body2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Text(
                                text = "Waiting for the phone…",
                                style = MaterialTheme.typography.body2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    is WearCommandState.Result -> {
                        item {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.body1,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                        item {
                            Chip(
                                label = { Text("Dismiss") },
                                onClick = { repository.dismissCommandState() },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    is WearCommandState.TimedOut -> {
                        item {
                            Text(
                                text = "No response from the phone for: ${state.text}",
                                style = MaterialTheme.typography.body1,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = "It may still finish on the phone — it was NOT marked done here.",
                                style = MaterialTheme.typography.body2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Chip(
                                label = { Text("Dismiss") },
                                onClick = { repository.dismissCommandState() },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // ── Recent tasks ───────────────────────────────────────
                val recent = status?.recentTasks.orEmpty()
                if (recent.isNotEmpty()) {
                    item {
                        Text(
                            text = "Recent tasks",
                            style = MaterialTheme.typography.title3,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        )
                    }
                    items(recent) { task ->
                        Text(
                            text = "${if (task.success) "✓" else "✗"} ${task.description}",
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = TIME_FORMAT.format(Date(task.atMs)),
                            style = MaterialTheme.typography.caption2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else if (status != null) {
                    item {
                        Text(
                            text = "No tasks yet",
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
