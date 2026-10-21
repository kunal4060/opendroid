package com.opendroid.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opendroid.ai.core.memory.graph.KnowledgeCategory
import com.opendroid.ai.core.memory.graph.KnowledgeNode
import com.opendroid.ai.core.memory.graph.MemoryProvenance
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.ui.screens.graph.GraphRenderModel
import com.opendroid.ai.ui.screens.graph.MemoryGraphView
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.MemoryViewModel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Storage-key prefix for knowledge-graph node rows; mirrors PersonalGrowthEngine. */
private const val PKG_NODE_KEY_PREFIX = "pkg_node_"

/** Vector glyph per memory tier — replaces the old emoji tier icons. */
fun memoryTierIcon(tier: MemoryTier): ImageVector = when (tier) {
    MemoryTier.TEMPORARY -> Icons.Rounded.Bolt
    MemoryTier.LONG_TERM -> Icons.Rounded.Psychology
    MemoryTier.LEARNED_PATTERN -> Icons.Rounded.TrendingUp
    MemoryTier.SENSITIVE -> Icons.Rounded.Lock
}

/** Tier colour by meaning: accent for long-term, steel info for learned
 *  patterns, amber for the locked sensitive tier, neutral for temporary. */
@Composable
fun memoryTierColor(tier: MemoryTier): Color = when (tier) {
    MemoryTier.TEMPORARY -> AppTheme.colors.textSecondary
    MemoryTier.LONG_TERM -> AppTheme.colors.accentNeonGreen
    MemoryTier.LEARNED_PATTERN -> AppTheme.colors.accentInfo
    MemoryTier.SENSITIVE -> AppTheme.colors.accentOrange
}

/** One-line explanation shown under each tier section header. */
fun memoryTierCaption(tier: MemoryTier): String = when (tier) {
    MemoryTier.LONG_TERM -> "Facts and knowledge kept until you delete them."
    MemoryTier.LEARNED_PATTERN -> "Patterns noticed from how you use the app."
    MemoryTier.SENSITIVE -> "Encrypted on this device; shown only after you unlock it."
    MemoryTier.TEMPORARY -> "Short-lived context for the current session."
}

/** Vector glyph per knowledge category — replaces the old emoji icons. */
fun memoryCategoryIcon(category: KnowledgeCategory): ImageVector = when (category) {
    KnowledgeCategory.CONTACT -> Icons.Rounded.Groups
    KnowledgeCategory.TASK_ROUTINE -> Icons.Rounded.Settings
    KnowledgeCategory.APP_PREFERENCE -> Icons.Rounded.Smartphone
    KnowledgeCategory.SCHEDULE -> Icons.Rounded.CalendarMonth
    KnowledgeCategory.PROJECT -> Icons.Rounded.Folder
    KnowledgeCategory.RESOURCE -> Icons.Rounded.Public
    KnowledgeCategory.NOTE_FACT -> Icons.Rounded.EditNote
    KnowledgeCategory.USER_PREFERENCE -> Icons.Rounded.Star
}

/**
 * Confidence as a quiet trust cue (research-2 3E.3): a slim 3-segment meter
 * plus a word, never a raw number. Segments derive from the node's stored
 * confidence exactly as the sort order does.
 */
@Composable
fun MemoryConfidenceMeter(confidence: Float, modifier: Modifier = Modifier) {
    val filled = when {
        confidence >= 0.66f -> 3
        confidence >= 0.33f -> 2
        confidence > 0f -> 1
        else -> 0
    }
    val word = when {
        confidence >= 0.66f -> "High confidence"
        confidence >= 0.33f -> "Medium confidence"
        else -> "Low confidence"
    }
    Row(
        modifier = modifier.semantics { contentDescription = word },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index < filled) AppTheme.colors.accentNeonGreen
                        else AppTheme.colors.borderColor
                    )
            )
        }
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = word,
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.colors.textSecondary
        )
    }
}

/** Shared honest empty state: an icon and one line, never filler content. */
@Composable
fun MemoryEmptyState(icon: ImageVector, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTheme.colors.textTertiary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

enum class MemoryScreenTab(val title: String) {
    GROWTH_GRAPH("Growth graph"),
    SEMANTIC("Long-term"),
    WORKING("Temporary"),
    EPISODIC("Episodic"),
    PROCEDURAL("Macros")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(MemoryScreenTab.GROWTH_GRAPH) }
    var searchQuery by remember { mutableStateOf("") }
    var isAddingFact by remember { mutableStateOf(false) }
    
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Personal memory",
                        color = AppTheme.colors.textPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                actions = {
                    TextButton(onClick = {
                        when (selectedTab) {
                            // The graph tab shows all four tiers — its wipe
                            // clears all of them (incl. the sensitive store),
                            // not just learned patterns.
                            MemoryScreenTab.GROWTH_GRAPH -> viewModel.clearAllGraphTiers()
                            MemoryScreenTab.SEMANTIC -> viewModel.clearMemories(MemoryType.SEMANTIC)
                            MemoryScreenTab.WORKING -> viewModel.clearMemories(MemoryType.WORKING)
                            MemoryScreenTab.EPISODIC -> viewModel.clearMemories(MemoryType.EPISODIC)
                            MemoryScreenTab.PROCEDURAL -> viewModel.clearMemories(MemoryType.PROCEDURAL)
                        }
                    }) {
                        Text(
                            if (selectedTab == MemoryScreenTab.GROWTH_GRAPH) "Wipe all tiers" else "Wipe category",
                            color = AppTheme.colors.accentRed,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.colors.background)
            )
        },
        containerColor = AppTheme.colors.background,
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Memory Category Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = AppTheme.colors.background,
                contentColor = AppTheme.colors.accentNeonGreen,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    if (selectedTab.ordinal in tabPositions.indices) {
                        TabRowDefaults.Indicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                            color = AppTheme.colors.accentNeonGreen
                        )
                    }
                },
                divider = { Divider(color = AppTheme.colors.borderSubtle) }
            ) {
                MemoryScreenTab.values().forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        selectedContentColor = AppTheme.colors.accentNeonGreen,
                        unselectedContentColor = AppTheme.colors.textSecondary,
                        onClick = { 
                            selectedTab = tab
                            searchQuery = "" // Reset search query when changing tabs
                            isAddingFact = false
                        },
                        text = {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar & Add Button (Conditional)
            if (selectedTab != MemoryScreenTab.WORKING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            val hint = when (selectedTab) {
                                MemoryScreenTab.GROWTH_GRAPH -> "Search Knowledge Graph..."
                                MemoryScreenTab.EPISODIC -> "Search conversation logs..."
                                MemoryScreenTab.PROCEDURAL -> "Search macros..."
                                else -> "Search facts..."
                            }
                            Text(hint, color = AppTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = AppTheme.colors.textSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.colors.accentNeonGreen,
                            unfocusedBorderColor = AppTheme.colors.borderColor,
                            focusedTextColor = AppTheme.colors.textPrimary,
                            unfocusedTextColor = AppTheme.colors.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedTab == MemoryScreenTab.SEMANTIC) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { isAddingFact = !isAddingFact },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(AppTheme.colors.accentGreenButton)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Memory", tint = AppTheme.colors.onAccent)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Render Dynamic Tab Contents
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    MemoryScreenTab.GROWTH_GRAPH -> {
                        KnowledgeGraphView(viewModel = viewModel, searchQuery = searchQuery)
                    }
                    MemoryScreenTab.WORKING -> {
                        WorkingMemoryView(viewModel = viewModel)
                    }
                    MemoryScreenTab.EPISODIC -> {
                        EpisodicMemoryView(viewModel = viewModel, searchQuery = searchQuery)
                    }
                    MemoryScreenTab.SEMANTIC -> {
                        SemanticMemoryView(
                            viewModel = viewModel,
                            searchQuery = searchQuery,
                            isAddingFact = isAddingFact,
                            onIsAddingFactChange = { isAddingFact = it },
                            newKey = newKey,
                            onNewKeyChange = { newKey = it },
                            newValue = newValue,
                            onNewValueChange = { newValue = it }
                        )
                    }
                    MemoryScreenTab.PROCEDURAL -> {
                        ProceduralMemoryView(viewModel = viewModel, searchQuery = searchQuery)
                    }
                }
            }
        }
    }
}

@Composable
fun WorkingMemoryView(viewModel: MemoryViewModel) {
    val activePlan by viewModel.activePlan.collectAsState()
    val workingMemory = viewModel.workingMemory
    
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. Device State Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AppTheme.colors.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active environment state",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StateItem("Battery level", "${workingMemory.batteryLevel}%", AppTheme.colors.textPrimary)
                        StateItem("Wi-Fi state", workingMemory.wifiState, if (workingMemory.wifiState == "Inactive") AppTheme.colors.accentRed else AppTheme.colors.textPrimary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StateItem("Connectivity", workingMemory.connectivity, AppTheme.colors.textPrimary)
                        StateItem("Internet", if (workingMemory.isInternetAvailable) "Available" else "Not available", if (workingMemory.isInternetAvailable) AppTheme.colors.textPrimary else AppTheme.colors.accentRed)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StateItem("Location context", workingMemory.locationContext, AppTheme.colors.textPrimary)
                    }
                }
            }
        }

        // 2. Active Plan Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AppTheme.colors.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active plan",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val plan = activePlan
                    if (plan != null) {
                        Text(
                            text = plan.goal,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = when (plan.status.name) {
                                    "RUNNING" -> AppTheme.colors.accentContainer
                                    "COMPLETED" -> AppTheme.colors.success.copy(alpha = 0.18f)
                                    else -> AppTheme.colors.accentRed.copy(alpha = 0.15f)
                                },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.padding(end = 6.dp)
                             ) {
                                Text(
                                    text = plan.status.name,
                                    color = when (plan.status.name) {
                                        "RUNNING" -> AppTheme.colors.onAccentContainer
                                        "COMPLETED" -> AppTheme.colors.success
                                        else -> AppTheme.colors.accentRed
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = AppTheme.colors.borderSubtle)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        plan.steps.forEachIndexed { index, step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    // D8: the old text status glyphs are
                                    // meaningless to a screen reader — the
                                    // merged stop speaks step, state and
                                    // outcome in words instead.
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = buildString {
                                            append("Step ${index + 1}, ")
                                            append(
                                                when (step.status.name) {
                                                    "COMPLETED" -> "completed"
                                                    "RUNNING" -> "running"
                                                    "FAILED" -> "failed"
                                                    else -> "pending"
                                                }
                                            )
                                            append(": ")
                                            append(step.description)
                                            if (!step.result.isNullOrBlank()) {
                                                append(". Result: ${step.result}")
                                            }
                                            if (!step.error.isNullOrBlank()) {
                                                append(". Error: ${step.error}")
                                            }
                                        }
                                    },
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = when (step.status.name) {
                                        "COMPLETED" -> Icons.Rounded.CheckCircle
                                        "RUNNING" -> Icons.Rounded.PlayCircle
                                        "FAILED" -> Icons.Rounded.Cancel
                                        else -> Icons.Rounded.Schedule
                                    },
                                    contentDescription = null,
                                    tint = when (step.status.name) {
                                        "COMPLETED" -> AppTheme.colors.success
                                        "RUNNING" -> AppTheme.colors.accentNeonGreen
                                        "FAILED" -> AppTheme.colors.accentRed
                                        else -> AppTheme.colors.textSecondary
                                    },
                                    modifier = Modifier
                                        .padding(end = 8.dp, top = 2.dp)
                                        .size(16.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${index + 1}. ${step.description}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (step.status.name == "COMPLETED") AppTheme.colors.textSecondary else AppTheme.colors.textPrimary,
                                        fontWeight = if (step.status.name == "RUNNING") FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (!step.result.isNullOrBlank()) {
                                        Text(
                                            text = "Result: ${step.result}",
                                            style = MonoMedium,
                                            color = AppTheme.colors.textSecondary,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    if (!step.error.isNullOrBlank()) {
                                        Text(
                                            text = "Error: ${step.error}",
                                            style = MonoMedium,
                                            color = AppTheme.colors.accentRed,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No active autonomous plan running.",
                                color = AppTheme.colors.textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // 3. Current Session history
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AppTheme.colors.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Working session history",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val history = workingMemory.conversationHistory
                    if (history.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            history.forEach { msg ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (msg.sender.name == "USER") Arrangement.End else Arrangement.Start
                                ) {
                                    Surface(
                                        color = if (msg.sender.name == "USER") AppTheme.colors.accentContainer else AppTheme.colors.surfaceRaised,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, AppTheme.colors.borderColor)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = if (msg.sender.name == "USER") "User" else "Agent",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (msg.sender.name == "USER") AppTheme.colors.onAccentContainer else AppTheme.colors.textSecondary
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = msg.text,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = AppTheme.colors.textPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No messages in current working session.",
                                color = AppTheme.colors.textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StateItem(label: String, value: String, valueColor: Color) {
    Column {
        Text(text = label, color = AppTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = valueColor, style = MonoMedium)
    }
}

@Composable
fun EpisodicMemoryView(viewModel: MemoryViewModel, searchQuery: String) {
    val conversations by viewModel.conversationHistory.collectAsState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    val filteredLogs = conversations.filter {
        it.text.contains(searchQuery, ignoreCase = true) ||
        (it.modelBadge?.contains(searchQuery, ignoreCase = true) ?: false)
    }

    if (filteredLogs.isNotEmpty()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredLogs) { log ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppTheme.colors.borderColor, RoundedCornerShape(18.dp)),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (log.sender.name == "USER") "User" else "Agent",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (log.sender.name == "USER") AppTheme.colors.accentNeonGreen else AppTheme.colors.textPrimary
                                )
                                log.modelBadge?.let { badge ->
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = AppTheme.colors.surfaceRaised,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = badge,
                                            style = MonoMedium,
                                            color = AppTheme.colors.textSecondary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = dateFormat.format(Date(log.timestamp)),
                                style = MonoMedium,
                                color = AppTheme.colors.textTertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = log.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.colors.textPrimary
                        )
                    }
                }
            }
        }
    } else {
        MemoryEmptyState(
            icon = Icons.Rounded.History,
            message = "No episodic chat logs recorded.",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun SemanticMemoryView(
    viewModel: MemoryViewModel,
    searchQuery: String,
    isAddingFact: Boolean,
    onIsAddingFactChange: (Boolean) -> Unit,
    newKey: String,
    onNewKeyChange: (String) -> Unit,
    newValue: String,
    onNewValueChange: (String) -> Unit
) {
    val allMemories by viewModel.memoriesList.collectAsState()
    
    val filteredMemories = allMemories.filter {
        it.type == MemoryType.SEMANTIC && (
            it.key.contains(searchQuery, ignoreCase = true) ||
            it.value.contains(searchQuery, ignoreCase = true)
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = isAddingFact) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .border(1.dp, AppTheme.colors.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Store a new memory fact",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = onNewKeyChange,
                        label = { Text("Fact key / identifier", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.colors.accentNeonGreen,
                            unfocusedBorderColor = AppTheme.colors.borderColor,
                            focusedTextColor = AppTheme.colors.textPrimary,
                            unfocusedTextColor = AppTheme.colors.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = onNewValueChange,
                        label = { Text("Fact content / details", style = MaterialTheme.typography.bodySmall) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.colors.accentNeonGreen,
                            unfocusedBorderColor = AppTheme.colors.borderColor,
                            focusedTextColor = AppTheme.colors.textPrimary,
                            unfocusedTextColor = AppTheme.colors.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onIsAddingFactChange(false) }) {
                            Text("Cancel", color = AppTheme.colors.accentRed)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newKey.isNotBlank() && newValue.isNotBlank()) {
                                    viewModel.storeFact(newKey, newValue, MemoryType.SEMANTIC)
                                    onNewKeyChange("")
                                    onNewValueChange("")
                                    onIsAddingFactChange(false)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.accentGreenButton, contentColor = AppTheme.colors.onAccent),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text("Save fact", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredMemories.isNotEmpty()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredMemories) { mem ->
                    MemoryItemCard(
                        memory = mem,
                        onDelete = {
                            // Knowledge-graph node rows must be deleted through
                            // the engine: a plain row delete would leave a
                            // Level 4 secret in the Keystore, and the node
                            // would resurrect from it in the next snapshot.
                            if (mem.key.startsWith(PKG_NODE_KEY_PREFIX)) {
                                viewModel.deleteKnowledgeNode(mem.key.removePrefix(PKG_NODE_KEY_PREFIX))
                            } else {
                                viewModel.deleteMemory(mem.key)
                            }
                        }
                    )
                }
            }
        } else {
            MemoryEmptyState(
                icon = Icons.Rounded.Description,
                message = "No semantic facts indexed in this category.",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
fun MemoryItemCard(
    memory: Memory,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AppTheme.colors.borderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.key,
                    style = MonoMedium,
                    color = AppTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = memory.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Indexed: ${dateFormat.format(Date(memory.timestamp))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.textTertiary
                )
                MemoryRowProvenance(memory = memory, onDelete = onDelete)
            }
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Memory",
                    tint = AppTheme.colors.textSecondary.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * "Why do you know this?" for a knowledge-graph node. The text comes from the
 * node's stored origin ([MemoryProvenance]) — if none was recorded, the panel
 * says so instead of inventing one. Delete goes through the same real path as
 * the card's delete (PersonalGrowthEngine.deleteNode via the ViewModel), so a
 * memory deleted here is gone from the graph and from recall.
 */
@Composable
fun MemoryProvenancePanel(
    node: KnowledgeNode,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }
    val formatDate: (Long) -> String = remember(dateFormat) {
        { millis -> dateFormat.format(Date(millis)) }
    }

    val rows = buildList {
        node.origin?.let { origin ->
            if (origin.createdAt > 0L) add("First learned" to formatDate(origin.createdAt))
            origin.sessionId?.let { add("Chat session" to it) }
        }
        add("Last updated" to formatDate(node.lastUpdated))
        if (node.accessCount > 1) add("Times observed" to node.accessCount.toString())
        if (node.tier == MemoryTier.LEARNED_PATTERN) {
            add("Confidence" to "${(node.confidence * 100).toInt()}%")
        }
    }

    ProvenanceExpander(
        headline = MemoryProvenance.headline(node, formatDate),
        rows = rows,
        onDelete = onDelete
    )
}

/**
 * Provenance for a plain semantic-memory row (Semantic tab). Rows that are
 * actually knowledge-graph nodes carry their origin inside the node JSON and
 * get the full panel; other rows never had their creation path recorded, so
 * they get the honest fallback — no reconstructed story.
 */
@Composable
fun MemoryRowProvenance(
    memory: Memory,
    onDelete: () -> Unit
) {
    val node = remember(memory.key, memory.value) {
        if (memory.key.startsWith(PKG_NODE_KEY_PREFIX)) {
            try {
                Json { ignoreUnknownKeys = true }.decodeFromString<KnowledgeNode>(memory.value)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    if (node != null) {
        MemoryProvenancePanel(node = node, onDelete = onDelete)
    } else {
        ProvenanceExpander(
            headline = MemoryProvenance.NOT_RECORDED_HEADLINE,
            rows = emptyList(),
            onDelete = onDelete
        )
    }
}

@Composable
private fun ProvenanceExpander(
    headline: String,
    rows: List<Pair<String, String>>,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.HelpOutline,
                contentDescription = null,
                tint = AppTheme.colors.accentNeonGreen,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Why do you know this?",
                style = MaterialTheme.typography.labelMedium,
                color = AppTheme.colors.accentNeonGreen,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide origin" else "Show origin",
                tint = AppTheme.colors.accentNeonGreen,
                modifier = Modifier.size(14.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
            ) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textPrimary
                )
                rows.forEach { (label, value) ->
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "$label: $value",
                        style = MonoMedium,
                        color = AppTheme.colors.textSecondary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = AppTheme.colors.accentRed,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete this memory", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.accentRed)
                    }
                }
            }
        }
    }
}

@Composable
fun ProceduralMemoryView(viewModel: MemoryViewModel, searchQuery: String) {
    val macros by viewModel.macrosList.collectAsState()

    val filteredMacros = macros.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
        it.trigger.contains(searchQuery, ignoreCase = true)
    }

    if (filteredMacros.isNotEmpty()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredMacros) { macro ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppTheme.colors.borderColor, RoundedCornerShape(18.dp)),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = macro.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AppTheme.colors.textPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = if (macro.isSystem) AppTheme.colors.accentContainer else AppTheme.colors.surfaceRaised,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (macro.isSystem) "System" else "User",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (macro.isSystem) AppTheme.colors.onAccentContainer else AppTheme.colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Trigger: \"${macro.trigger}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Actions",
                                style = MaterialTheme.typography.labelMedium,
                                color = AppTheme.colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            macro.steps.forEachIndexed { index, step ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowForward,
                                        contentDescription = null,
                                        tint = AppTheme.colors.accentNeonGreen,
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .size(12.dp)
                                    )
                                    Text(
                                        text = step.description,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AppTheme.colors.textSecondary
                                    )
                                }
                            }
                        }
                        
                        if (!macro.isSystem) {
                            IconButton(onClick = { viewModel.deleteMacro(macro.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Macro",
                                    tint = AppTheme.colors.accentRed.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        MemoryEmptyState(
            icon = Icons.Rounded.Bolt,
            message = "No custom macros or procedures registered.",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun KnowledgeGraphView(
    viewModel: MemoryViewModel,
    searchQuery: String
) {
    val graph by viewModel.knowledgeGraph.collectAsState()
    var selectedTierFilter by remember { mutableStateOf<MemoryTier?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf<KnowledgeCategory?>(null) }
    var isAddingKnowledge by remember { mutableStateOf(false) }
    var addIsSensitive by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }
    var newSummary by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf(KnowledgeCategory.NOTE_FACT) }
    // C5: List | Graph — the graph renders the same filtered nodes below.
    var showGraph by remember { mutableStateOf(false) }

    val allNodes = graph.nodes.values.toList()
    val filteredNodes = allNodes.filter { node ->
        (selectedTierFilter == null || node.tier == selectedTierFilter) &&
        (selectedCategoryFilter == null || node.category == selectedCategoryFilter) &&
        (searchQuery.isBlank() ||
            node.label.contains(searchQuery, ignoreCase = true) ||
            node.summary.contains(searchQuery, ignoreCase = true) ||
            node.properties.values.any { it.contains(searchQuery, ignoreCase = true) }
        )
    }.sortedWith(
        compareBy<KnowledgeNode> {
            when (it.tier) {
                MemoryTier.LONG_TERM -> 0
                MemoryTier.LEARNED_PATTERN -> 1
                MemoryTier.SENSITIVE -> 2
                MemoryTier.TEMPORARY -> 3
            }
        }.thenByDescending { it.confidence }
         .thenByDescending { it.lastUpdated }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Tier Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = selectedTierFilter == null,
                    onClick = { selectedTierFilter = null },
                    label = { Text("All tiers (${allNodes.size})", style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppTheme.colors.accentContainer,
                        selectedLabelColor = AppTheme.colors.onAccentContainer,
                        containerColor = AppTheme.colors.cardBackground,
                        labelColor = AppTheme.colors.textSecondary
                    )
                )
            }
            items(MemoryTier.values()) { tier ->
                val count = allNodes.count { it.tier == tier }
                FilterChip(
                    selected = selectedTierFilter == tier,
                    onClick = { selectedTierFilter = if (selectedTierFilter == tier) null else tier },
                    leadingIcon = {
                        Icon(
                            imageVector = memoryTierIcon(tier),
                            contentDescription = null,
                            tint = memoryTierColor(tier),
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = { Text("${GraphRenderModel.tierDisplayName(tier)} ($count)", style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (tier == MemoryTier.SENSITIVE) {
                            AppTheme.colors.accentOrange
                        } else {
                            AppTheme.colors.accentContainer
                        },
                        selectedLabelColor = if (tier == MemoryTier.SENSITIVE) {
                            AppTheme.colors.background
                        } else {
                            AppTheme.colors.onAccentContainer
                        },
                        containerColor = AppTheme.colors.cardBackground,
                        labelColor = AppTheme.colors.textSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Category Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = selectedCategoryFilter == null,
                    onClick = { selectedCategoryFilter = null },
                    label = { Text("All categories", style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppTheme.colors.accentContainer,
                        selectedLabelColor = AppTheme.colors.onAccentContainer,
                        containerColor = AppTheme.colors.cardBackground,
                        labelColor = AppTheme.colors.textSecondary
                    )
                )
            }
            items(KnowledgeCategory.values()) { cat ->
                FilterChip(
                    selected = selectedCategoryFilter == cat,
                    onClick = { selectedCategoryFilter = if (selectedCategoryFilter == cat) null else cat },
                    leadingIcon = {
                        Icon(
                            imageVector = memoryCategoryIcon(cat),
                            contentDescription = null,
                            tint = AppTheme.colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = {
                        Text(
                            cat.name.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppTheme.colors.accentContainer,
                        selectedLabelColor = AppTheme.colors.onAccentContainer,
                        containerColor = AppTheme.colors.cardBackground,
                        labelColor = AppTheme.colors.textSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Add Knowledge Header Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Knowledge (${filteredNodes.size})",
                style = MaterialTheme.typography.titleSmall,
                color = AppTheme.colors.textPrimary
            )
            TextButton(onClick = { isAddingKnowledge = !isAddingKnowledge }) {
                Icon(Icons.Default.Add, contentDescription = null, tint = AppTheme.colors.accentNeonGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isAddingKnowledge) "Close" else "Add entity / secret", color = AppTheme.colors.accentNeonGreen, style = MaterialTheme.typography.labelLarge)
            }
        }

        // Add Knowledge / Secret Card
        AnimatedVisibility(visible = isAddingKnowledge) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .border(1.dp, AppTheme.colors.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (addIsSensitive) "Add an encrypted secret" else "Add long-term knowledge",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (addIsSensitive) AppTheme.colors.accentOrange else AppTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = !addIsSensitive,
                            onClick = { addIsSensitive = false },
                            leadingIcon = {
                                Icon(Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            label = { Text("Long-term memory", style = MaterialTheme.typography.labelMedium) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = addIsSensitive,
                            onClick = { addIsSensitive = true },
                            leadingIcon = {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            label = { Text("Keystore encrypted", style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text(if (addIsSensitive) "Secret key / label (e.g. locker_code)" else "Label / title (e.g. Favorite Coffee)", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (addIsSensitive) AppTheme.colors.accentOrange else AppTheme.colors.accentNeonGreen,
                            unfocusedBorderColor = AppTheme.colors.borderColor,
                            focusedTextColor = AppTheme.colors.textPrimary,
                            unfocusedTextColor = AppTheme.colors.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newSummary,
                        onValueChange = { newSummary = it },
                        label = { Text(if (addIsSensitive) "Secret value (hardware encrypted)" else "Details / description", style = MaterialTheme.typography.bodySmall) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (addIsSensitive) AppTheme.colors.accentOrange else AppTheme.colors.accentNeonGreen,
                            unfocusedBorderColor = AppTheme.colors.borderColor,
                            focusedTextColor = AppTheme.colors.textPrimary,
                            unfocusedTextColor = AppTheme.colors.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!addIsSensitive) {
                        // Category picker — newCategory used to be stuck on
                        // USER_PREFERENCE with no control anywhere, so every
                        // entity added here was misfiled under it.
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Category",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.textSecondary
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(KnowledgeCategory.values()) { cat ->
                                FilterChip(
                                    selected = newCategory == cat,
                                    onClick = { newCategory = cat },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = memoryCategoryIcon(cat),
                                            contentDescription = null,
                                            tint = AppTheme.colors.textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            cat.name.replace('_', ' ').lowercase()
                                                .replaceFirstChar { it.uppercase() },
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AppTheme.colors.accentContainer,
                                        selectedLabelColor = AppTheme.colors.onAccentContainer,
                                        containerColor = AppTheme.colors.cardBackground,
                                        labelColor = AppTheme.colors.textSecondary
                                    )
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { isAddingKnowledge = false }) {
                            Text("Cancel", color = AppTheme.colors.accentRed)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newLabel.isNotBlank() && newSummary.isNotBlank()) {
                                    if (addIsSensitive) {
                                        viewModel.recordSensitiveData(newLabel, newSummary, newLabel)
                                    } else {
                                        viewModel.recordExplicitKnowledge(newLabel, newSummary, newCategory)
                                    }
                                    newLabel = ""
                                    newSummary = ""
                                    isAddingKnowledge = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (addIsSensitive) AppTheme.colors.accentOrange else AppTheme.colors.accentGreenButton,
                                contentColor = if (addIsSensitive) AppTheme.colors.background else AppTheme.colors.onAccent
                            ),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text("Save entry", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // List | Graph toggle (C5). Both views read the same
        // PersonalKnowledgeGraph StateFlow and the same filter state above;
        // the list stays as the screen-reader friendly equivalent.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showGraph,
                onClick = { showGraph = false },
                leadingIcon = {
                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                label = { Text("List", style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppTheme.colors.accentContainer,
                    selectedLabelColor = AppTheme.colors.onAccentContainer,
                    containerColor = AppTheme.colors.cardBackground,
                    labelColor = AppTheme.colors.textSecondary
                )
            )
            FilterChip(
                selected = showGraph,
                onClick = { showGraph = true },
                leadingIcon = {
                    Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                label = { Text("Graph", style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppTheme.colors.accentContainer,
                    selectedLabelColor = AppTheme.colors.onAccentContainer,
                    containerColor = AppTheme.colors.cardBackground,
                    labelColor = AppTheme.colors.textSecondary
                )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (showGraph) {
            MemoryGraphView(
                viewModel = viewModel,
                filteredNodes = filteredNodes,
                relations = graph.relations,
                totalNodeCount = allNodes.size,
                modifier = Modifier.weight(1f)
            )
        } else if (filteredNodes.isNotEmpty()) {
            // Nodes arrive sorted tier-first (see above), so grouping by tier
            // here is presentation only: a plain-language header + caption
            // per tier section, in the same order.
            val tierOrder = listOf(
                MemoryTier.LONG_TERM,
                MemoryTier.LEARNED_PATTERN,
                MemoryTier.SENSITIVE,
                MemoryTier.TEMPORARY
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.weight(1f)
            ) {
                tierOrder.forEach { tier ->
                    val tierNodes = filteredNodes.filter { it.tier == tier }
                    if (tierNodes.isNotEmpty()) {
                        item(key = "tier-header-${tier.name}") {
                            TierSectionHeader(tier = tier)
                        }
                        items(tierNodes, key = { it.id }) { node ->
                            KnowledgeNodeCard(
                                node = node,
                                onPromote = { viewModel.promotePattern(node.id) },
                                onDelete = { viewModel.deleteKnowledgeNode(node.id) }
                            )
                        }
                    }
                }
            }
        } else {
            MemoryEmptyState(
                icon = Icons.Default.AccountTree,
                message = "No knowledge graph entities matching filter.",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

/** Plain-language tier section header: vector icon, name, one-line caption. */
@Composable
private fun TierSectionHeader(tier: MemoryTier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    ) {
        Icon(
            imageVector = memoryTierIcon(tier),
            contentDescription = null,
            tint = memoryTierColor(tier),
            modifier = Modifier.size(18.dp)
        )
        Column {
            Text(
                text = GraphRenderModel.tierDisplayName(tier),
                style = MaterialTheme.typography.titleSmall,
                color = AppTheme.colors.textPrimary
            )
            Text(
                text = memoryTierCaption(tier),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.textSecondary
            )
        }
    }
}

@Composable
fun KnowledgeNodeCard(
    node: KnowledgeNode,
    onPromote: () -> Unit,
    onDelete: () -> Unit
) {
    val tierColor = memoryTierColor(node.tier)
    val isSensitive = node.tier == MemoryTier.SENSITIVE

    // Sensitive memories get the trust treatment (research-2 3E.4): a
    // distinct raised surface and an amber hairline, with the lock glyph.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isSensitive) AppTheme.colors.accentOrange.copy(alpha = 0.45f)
                else AppTheme.colors.borderColor,
                RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSensitive) AppTheme.colors.surfaceRaised
            else AppTheme.colors.cardBackground
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = AppTheme.colors.surfaceOverlay,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, AppTheme.colors.borderColor)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = memoryTierIcon(node.tier),
                                contentDescription = null,
                                tint = tierColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = GraphRenderModel.tierDisplayName(node.tier),
                                style = MaterialTheme.typography.labelMedium,
                                color = tierColor
                            )
                        }
                    }
                    Surface(
                        color = AppTheme.colors.surfaceOverlay,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = node.category.name.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTheme.colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (node.tier == MemoryTier.LEARNED_PATTERN) {
                        MemoryConfidenceMeter(confidence = node.confidence)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Node",
                            tint = AppTheme.colors.textSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = node.label,
                style = MaterialTheme.typography.titleSmall,
                color = AppTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = node.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.textPrimary
            )

            if (node.properties.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    node.properties.entries.take(3).forEach { (k, v) ->
                        Text(
                            text = "$k: $v",
                            style = MonoMedium,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            MemoryProvenancePanel(node = node, onDelete = onDelete)

            if (node.tier == MemoryTier.LEARNED_PATTERN) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onPromote,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AppTheme.colors.borderStrong),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Rounded.TrendingUp, contentDescription = null, tint = AppTheme.colors.accentNeonGreen, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Promote to long-term", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.accentNeonGreen)
                    }
                }
            }
        }
    }
}
