package com.opendroid.ai.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.google.CalendarEventSource
import com.opendroid.ai.core.google.GoogleCalendarEvent
import com.opendroid.ai.core.google.GoogleScopes
import com.opendroid.ai.core.google.NewCalendarEvent
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AccentOrange
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.DarkBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.GoogleAccountViewModel
import com.opendroid.ai.ui.viewmodel.GoogleUiEvent
import com.opendroid.ai.ui.viewmodel.GoogleUiState
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * Settings → Connected accounts → Google (feature D6).
 *
 * Three honest states, in order:
 *  1. The build has no OAuth web client ID → a setup-needed card with the
 *     exact steps (never a dead button, never fake data). On-device
 *     calendar below still works.
 *  2. Configured but not connected → Connect runs the real Credential
 *     Manager + AuthorizationClient flow.
 *  3. Connected → account + granted scopes, Calendar API events (+ create),
 *     Gmail behind its own incremental grant. A dead grant shows the
 *     reconnect prompt; device calendar data is always labelled as such.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleAccountScreen(
    viewModel: GoogleAccountViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findGoogleActivity() }

    val resolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val host = activity ?: return@rememberLauncherForActivityResult
        viewModel.onResolutionFinished(host, result.resultCode == Activity.RESULT_OK)
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.onLocalCalendarPermissionResult() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GoogleUiEvent.LaunchResolution -> {
                    resolutionLauncher.launch(
                        IntentSenderRequest.Builder(event.pendingIntent.intentSender).build()
                    )
                }
            }
        }
    }

    LaunchedEffect(activity) {
        activity?.let { viewModel.onShown(it) }
    }

    var showCreateEvent by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "GOOGLE ACCOUNT",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            if (!state.configured) {
                item { GoogleSetupNeededCard() }
            }

            if (state.configured && !state.isConnected) {
                item {
                    GoogleConnectCard(
                        loading = state.loading,
                        onConnect = { activity?.let { viewModel.connect(it) } }
                    )
                }
            }

            if (state.isConnected) {
                item {
                    GoogleAccountCard(
                        state = state,
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
            }

            if (state.needsReconnect) {
                item {
                    GoogleReconnectCard(
                        onReconnect = { activity?.let { viewModel.reconnect(it) } }
                    )
                }
            }

            state.error?.let { message ->
                item {
                    Text(
                        text = message,
                        color = AccentRed,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AccentRed.copy(alpha = 0.08f))
                            .border(1.dp, AccentRed.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    )
                }
            }

            if (state.loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(28.dp))
                    }
                }
            }

            // ─── Calendar ────────────────────────────────────────────────
            item {
                GoogleSectionHeader(
                    icon = Icons.Default.Event,
                    title = "CALENDAR",
                    sourceLabel = when (state.calendarSource) {
                        CalendarEventSource.GOOGLE_API -> "GOOGLE CALENDAR API"
                        CalendarEventSource.DEVICE -> "ON-DEVICE CALENDARS"
                        null -> null
                    }
                )
            }

            if (state.calendarSource == CalendarEventSource.DEVICE && !state.localCalendarPermissionGranted) {
                item {
                    GoogleInfoCard(
                        text = "OpenDroid needs the calendar permission to show your on-device " +
                            "events. These come from calendars already synced to this phone."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            calendarPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) {
                        Text("Grant calendar permission", color = DarkBackground)
                    }
                }
            }

            if (state.calendarEvents.isEmpty()) {
                item {
                    GoogleInfoCard(
                        text = if (state.calendarSource == CalendarEventSource.GOOGLE_API) {
                            "No upcoming events on your Google calendar."
                        } else {
                            "No upcoming on-device events in the next 30 days."
                        }
                    )
                }
            } else {
                items(state.calendarEvents, key = { it.source.name + it.id + it.startMillis }) { event ->
                    GoogleEventCard(event = event)
                }
            }

            if (state.isConnected && state.hasCalendarScope) {
                item {
                    OutlinedButton(
                        onClick = { showCreateEvent = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = AccentCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Google Calendar event", color = AccentCyan)
                    }
                }
            }

            // ─── Gmail ───────────────────────────────────────────────────
            item {
                GoogleSectionHeader(
                    icon = Icons.Default.Email,
                    title = "GMAIL",
                    sourceLabel = if (state.hasGmailScope) "GMAIL API · READ-ONLY" else null
                )
            }

            item {
                GoogleMailSection(
                    state = state,
                    onEnableMail = { activity?.let { viewModel.enableMail(it) } },
                    onSearch = { query -> activity?.let { viewModel.searchMail(it, query) } },
                    onOpenMessage = { id -> activity?.let { viewModel.openMessage(it, id) } },
                    onCloseMessage = { viewModel.closeMessage() }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showCreateEvent) {
        CreateGoogleEventDialog(
            onDismiss = { showCreateEvent = false },
            onCreate = { draft ->
                showCreateEvent = false
                activity?.let { viewModel.createEvent(it, draft) }
            }
        )
    }
}

/** Settings list entry for the Google account screen (pattern of B5's card). */
@Composable
fun GoogleAccountSettingsCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "Google account",
                tint = AccentCyan,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GOOGLE ACCOUNT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Connect Google for Calendar events and Gmail (read-only) via the official APIs",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "Go",
                tint = TextSecondary
            )
        }
    }
}

@Composable
private fun GoogleSetupNeededCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentOrange.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "DEVELOPER SETUP NEEDED",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentOrange,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "This build has no Google OAuth client configured, so account " +
                    "connection is disabled — Google sign-in cannot work without a " +
                    "Google Cloud project registered for this app. Nothing here is " +
                    "simulated: the calendar below is your real on-device calendar.",
                fontSize = 13.sp,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "To enable it (one-time, ~10 minutes):\n" +
                    "1. Create a Google Cloud project and enable the Google Calendar API and Gmail API.\n" +
                    "2. Configure the OAuth consent screen (External, Testing is fine).\n" +
                    "3. Add an Android OAuth client: package com.opendroid.aiagent + your signing certificate's SHA-1.\n" +
                    "4. Add a Web OAuth client and put its ID in ~/.gradle/gradle.properties as GOOGLE_WEB_CLIENT_ID.\n" +
                    "5. Rebuild the app.",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun GoogleConnectCard(loading: Boolean, onConnect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Not connected",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Connect your Google account to read your calendar and create " +
                    "events through the Google Calendar API. Gmail access is a separate, " +
                    "optional grant you can enable afterwards.",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect Google account", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GoogleAccountCard(state: GoogleUiState, onDisconnect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.accountName ?: state.accountEmail.orEmpty(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    if (state.accountName != null) {
                        Text(text = state.accountEmail.orEmpty(), fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "GRANTED ACCESS",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (state.grantedScopes.isEmpty()) {
                Text(
                    text = "Signed in, but no API access granted yet.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            } else {
                state.grantedScopes.forEach { scope ->
                    Text(
                        text = "• " + GoogleScopes.labelFor(scope),
                        fontSize = 12.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect", color = AccentRed)
            }
            Text(
                text = "Disconnect revokes this app's token and wipes the stored grant on " +
                    "this device. You can also remove access any time from your Google " +
                    "Account permissions page.",
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun GoogleReconnectCard(onReconnect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentOrange.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Connection needs attention",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Google no longer accepts this app's authorization — the grant " +
                    "expired or was revoked. Reconnect to keep using the Calendar and " +
                    "Gmail APIs. (While a Cloud project is in Testing status, grants " +
                    "expire after 7 days.)",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onReconnect,
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reconnect Google account", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GoogleSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sourceLabel: String?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp
        )
        if (sourceLabel != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = sourceLabel,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = AccentCyan,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AccentCyan.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun GoogleInfoCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = text, fontSize = 13.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun GoogleEventCard(event: GoogleCalendarEvent) {
    val dayFormat = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = event.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            val whenText = if (event.allDay) {
                dayFormat.format(Date(event.startMillis)) + " · All day"
            } else {
                dayFormat.format(Date(event.startMillis)) + " · " +
                    timeFormat.format(Date(event.startMillis)) + " – " +
                    timeFormat.format(Date(event.endMillis))
            }
            Text(text = whenText, fontSize = 12.sp, color = AccentCyan, fontFamily = FontFamily.Monospace)
            event.location?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            event.calendarName?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun GoogleMailSection(
    state: GoogleUiState,
    onEnableMail: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenMessage: (String) -> Unit,
    onCloseMessage: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    Column {
        when {
            !state.configured -> GoogleInfoCard(
                text = "Gmail access needs the developer setup above. Mail is never " +
                    "simulated — without a connected account there is simply no mail here."
            )

            !state.isConnected -> GoogleInfoCard(
                text = "Connect your Google account above first; Gmail access is then " +
                    "enabled separately, only if you want it."
            )

            !state.hasGmailScope -> {
                GoogleInfoCard(
                    text = "Gmail is read-only and off until you enable it. Note: reading " +
                        "mail is a Google \"Restricted\" scope — while the Cloud project is " +
                        "in Testing status it works for added test users, and the grant " +
                        "expires after 7 days."
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onEnableMail,
                    enabled = !state.loading,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable Gmail access (read-only)", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            }

            else -> {
                val opened = state.openedMessage
                if (opened != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = opened.subject,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = onCloseMessage) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close message",
                                        tint = TextSecondary
                                    )
                                }
                            }
                            Text(text = "From: ${opened.from}", fontSize = 12.sp, color = TextSecondary)
                            if (opened.to.isNotBlank()) {
                                Text(text = "To: ${opened.to}", fontSize = 12.sp, color = TextSecondary)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = opened.bodyText.ifBlank { opened.snippet },
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search mail (Gmail syntax)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onSearch(query) },
                            enabled = !state.loading,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = DarkBackground
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    if (!state.mailLoaded) {
                        GoogleInfoCard(
                            text = "Search to load mail — try \"is:unread\", \"from:…\" or " +
                                "leave it empty for the most recent messages."
                        )
                    } else if (state.messages.isEmpty()) {
                        GoogleInfoCard(text = "No messages matched.")
                    } else {
                        val dateFormat = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.messages.forEach { message ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                                        .clickable { onOpenMessage(message.id) },
                                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = message.from,
                                                fontSize = 13.sp,
                                                fontWeight = if (message.unread) FontWeight.Bold else FontWeight.Medium,
                                                color = TextPrimary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            message.dateMillis?.let {
                                                Text(
                                                    text = dateFormat.format(Date(it)),
                                                    fontSize = 11.sp,
                                                    color = TextSecondary
                                                )
                                            }
                                        }
                                        Text(
                                            text = message.subject,
                                            fontSize = 13.sp,
                                            fontWeight = if (message.unread) FontWeight.SemiBold else FontWeight.Normal,
                                            color = TextPrimary,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                        Text(
                                            text = message.snippet,
                                            fontSize = 12.sp,
                                            color = TextSecondary,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGoogleEventDialog(
    onDismiss: () -> Unit,
    onCreate: (NewCalendarEvent) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }

    val now = remember { System.currentTimeMillis() }
    var dateMillis by remember { mutableLongStateOf(now) }
    var startHour by remember { mutableIntStateOf(10) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(11) }
    var endMinute by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<String?>(null) } // "date" | "start" | "end"

    val dateLabel = remember(dateMillis) {
        SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(dateMillis))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New calendar event", color = TextPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { picker = "date" }) {
                    Text("Date: $dateLabel", color = AccentCyan)
                }
                TextButton(onClick = { picker = "start" }) {
                    Text("Starts: %02d:%02d".format(startHour, startMinute), color = AccentCyan)
                }
                TextButton(onClick = { picker = "end" }) {
                    Text("Ends: %02d:%02d".format(endHour, endMinute), color = AccentCyan)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    val zone = ZoneId.systemDefault()
                    val start = date.atTime(startHour, startMinute).atZone(zone).toInstant().toEpochMilli()
                    var end = date.atTime(endHour, endMinute).atZone(zone).toInstant().toEpochMilli()
                    if (end <= start) end = start + 60 * 60 * 1000L
                    onCreate(
                        NewCalendarEvent(
                            title = title.trim(),
                            description = description.trim().ifBlank { null },
                            location = location.trim().ifBlank { null },
                            startMillis = start,
                            endMillis = end
                        )
                    )
                },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("Create", color = DarkBackground)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )

    when (picker) {
        "date" -> {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { dateMillis = it }
                        picker = null
                    }) { Text("OK", color = AccentCyan) }
                },
                dismissButton = {
                    TextButton(onClick = { picker = null }) { Text("Cancel", color = TextSecondary) }
                }
            ) {
                DatePicker(state = datePickerState, showModeToggle = false)
            }
        }

        "start", "end" -> {
            val isStart = picker == "start"
            val timePickerState = rememberTimePickerState(
                initialHour = if (isStart) startHour else endHour,
                initialMinute = if (isStart) startMinute else endMinute,
                is24Hour = false
            )
            AlertDialog(
                onDismissRequest = { picker = null },
                title = { Text(if (isStart) "Start time" else "End time", color = TextPrimary) },
                text = { TimePicker(state = timePickerState) },
                confirmButton = {
                    TextButton(onClick = {
                        if (isStart) {
                            startHour = timePickerState.hour
                            startMinute = timePickerState.minute
                        } else {
                            endHour = timePickerState.hour
                            endMinute = timePickerState.minute
                        }
                        picker = null
                    }) { Text("OK", color = AccentCyan) }
                },
                dismissButton = {
                    TextButton(onClick = { picker = null }) { Text("Cancel", color = TextSecondary) }
                }
            )
        }
    }
}

// Named findGoogleActivity, not findActivity: PermissionsScreen.kt in this
// same package already declares `fun Context.findActivity(): ComponentActivity?`.
// Return type is not part of a JVM/Kotlin overload signature, so a second
// Context.findActivity() here (returning Activity?) was a conflicting overload
// and made every call site ambiguous — which in turn made `activity`'s type
// uninferable and cascaded into every `activity?.let { … }` in this file.
private tailrec fun Context.findGoogleActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findGoogleActivity()
    else -> null
}
