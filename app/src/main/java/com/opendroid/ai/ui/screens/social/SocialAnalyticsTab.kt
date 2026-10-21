package com.opendroid.ai.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.social.core.analytics.AnalyticsSummary
import com.opendroid.ai.social.domain.model.PostStatus
import com.opendroid.ai.social.domain.model.SocialAccount
import com.opendroid.ai.social.domain.model.SocialPlatform
import com.opendroid.ai.social.domain.model.SocialPost
import com.opendroid.ai.ui.components.DonutChart
import com.opendroid.ai.ui.components.SimpleBarChart
import com.opendroid.ai.ui.components.SimpleLineChart
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.JetBrainsMonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SocialAnalyticsTab(
    summary: AnalyticsSummary?,
    accounts: List<SocialAccount>,
    posts: List<SocialPost>,
    onRefresh: () -> Unit
) {
    val theme = AppTheme.colors
    var selectedRangeDays by remember { mutableStateOf(7) }

    // Every series below is derived from the real posts in the selected
    // range (this tab previously rendered hardcoded constant lists and
    // the range chips changed nothing). Published posts are bucketed
    // into 7 equal slices of the range; per-post reach/impressions are
    // the stored values synced from the platforms.
    val rangeData = remember(selectedRangeDays, posts) {
        val now = System.currentTimeMillis()
        val rangeMillis = selectedRangeDays * 86_400_000L
        val rangeStart = now - rangeMillis
        val inRange = posts.filter {
            it.status == PostStatus.PUBLISHED &&
                (it.publishedTime ?: it.createdAt) >= rangeStart
        }
        val bucketCount = 7
        val bucketMillis = (rangeMillis / bucketCount).coerceAtLeast(1L)
        fun bucketOf(time: Long): Int =
            ((time - rangeStart) / bucketMillis).toInt().coerceIn(0, bucketCount - 1)
        val labelFormat = SimpleDateFormat("d MMM", Locale.getDefault())
        val labels = (0 until bucketCount).map {
            labelFormat.format(Date(rangeStart + it * bucketMillis))
        }
        val reachPerBucket = FloatArray(bucketCount)
        val impressionsPerBucket = FloatArray(bucketCount)
        for (post in inRange) {
            val idx = bucketOf(post.publishedTime ?: post.createdAt)
            reachPerBucket[idx] += post.reach.toFloat()
            impressionsPerBucket[idx] += post.impressions.toFloat()
        }
        val cumulativeReach = reachPerBucket.runningFold(0f) { acc, v -> acc + v }.drop(1)

        // Platform share by reach (post counts when no reach recorded).
        val reachByPlatform = inRange.groupBy { it.platform }
            .mapValues { (_, ps) -> ps.sumOf { it.reach }.toFloat() }
        val shareBasis: Map<SocialPlatform, Float> =
            if (reachByPlatform.values.sum() > 0f) {
                reachByPlatform
            } else {
                inRange.groupBy { it.platform }.mapValues { (_, ps) -> ps.size.toFloat() }
            }
        val sortedShares = shareBasis.entries.sortedByDescending { it.value }
        val topShares = sortedShares.take(3).map { it.key as SocialPlatform? to it.value }
        val othersTotal = sortedShares.drop(3).sumOf { it.value.toDouble() }.toFloat()
        val donutEntries: List<Pair<SocialPlatform?, Float>> =
            topShares + (if (othersTotal > 0f) listOf(null to othersTotal) else emptyList())
        val donutTotal = donutEntries.sumOf { it.second.toDouble() }.toFloat()

        AnalyticsRangeData(
            bucketLabels = labels,
            cumulativeReachSeries = if (inRange.isEmpty()) emptyList() else cumulativeReach,
            impressionsSeries = if (inRange.isEmpty()) emptyList() else impressionsPerBucket.toList(),
            donutEntries = donutEntries,
            donutTotal = donutTotal
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. TIME RANGE SELECTOR & HEADER ──────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Performance Analytics", color = theme.textPrimary, style = MaterialTheme.typography.titleMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7 to "7D", 30 to "30D", 90 to "90D").forEach { (days, label) ->
                        FilterChip(
                            selected = selectedRangeDays == days,
                            onClick = { selectedRangeDays = days },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = theme.accentContainer,
                                selectedLabelColor = theme.onAccentContainer
                            )
                        )
                    }
                }
            }
        }

        // ── 2. METRIC CARDS ROW ──────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsMetricBox(
                    title = "Followers",
                    value = summary?.totalFollowers?.toString() ?: "0",
                    delta = summary?.followersGrowthDelta?.let { if (it >= 0) "+$it" else "$it" } ?: "+0",
                    modifier = Modifier.weight(1f)
                )
                AnalyticsMetricBox(
                    title = "Total Reach",
                    value = summary?.totalReach?.let { formatCount(it.toLong()) } ?: "0",
                    delta = summary?.reachGrowthDelta?.let { if (it >= 0) "+$it" else "$it" } ?: "+0",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsMetricBox(
                    title = "Engagement",
                    value = summary?.averageEngagementRate?.let { "${String.format("%.1f", it)}%" } ?: "0.0%",
                    delta = "Active",
                    modifier = Modifier.weight(1f)
                )
                AnalyticsMetricBox(
                    title = "Total Posts",
                    value = summary?.totalPosts?.toString() ?: "0",
                    delta = "Published",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── 3. FOLLOWER GROWTH TREND (LINE CHART) ────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Reach Growth Trend", color = theme.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Cumulative reach of posts published in the period", color = theme.textTertiary, style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    SimpleLineChart(
                        dataPoints = rangeData.cumulativeReachSeries,
                        labels = rangeData.bucketLabels,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        lineColor = theme.accentNeonGreen,
                        gridColor = theme.borderColor
                    )
                }
            }
        }

        // ── 4. DAILY REACH & ENGAGEMENT (BAR CHART) ──────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Impressions by Period Slice", color = theme.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Organic post visibility across all channels", color = theme.textTertiary, style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (rangeData.impressionsSeries.isEmpty()) {
                        Text(
                            "No published posts in this range.",
                            color = theme.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        SimpleBarChart(
                            values = rangeData.impressionsSeries,
                            labels = rangeData.bucketLabels,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            barColor = theme.accentInfo
                        )
                    }
                }
            }
        }

        // ── 5. PLATFORM SHARE (DONUT CHART) ──────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Reach Share by Platform", color = theme.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        // Series palette: accent / info / warning / neutral only.
                        val donutColors = listOf(
                            theme.accentNeonGreen,
                            theme.accentInfo,
                            theme.accentOrange,
                            theme.textSecondary
                        )

                        DonutChart(
                            proportions = rangeData.donutEntries.map { it.second },
                            colors = donutColors,
                            modifier = Modifier.size(100.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (rangeData.donutEntries.isEmpty()) {
                                Text(
                                    "No published posts in this range.",
                                    color = theme.textSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                rangeData.donutEntries.forEachIndexed { index, (platform, value) ->
                                    val pct = if (rangeData.donutTotal > 0f) {
                                        (value / rangeData.donutTotal * 100f).toInt()
                                    } else {
                                        0
                                    }
                                    LegendItem(
                                        label = platform?.displayName ?: "Others",
                                        percent = "$pct%",
                                        color = donutColors[index % donutColors.size]
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 6. PLATFORM BREAKDOWN CARDS ──────────────────────────────
        item {
            Text("Channel Breakdown", color = theme.textPrimary, style = MaterialTheme.typography.titleMedium)
        }

        if (accounts.isEmpty()) {
            item {
                Text("No accounts connected yet.", color = theme.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(accounts) { account ->
                val accountPosts = posts.filter { it.accountId == account.id || it.platform == account.platform }
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(theme.surfaceRaised)
                                .border(0.5.dp, theme.borderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(account.platform.displayName.take(1), color = theme.accentNeonGreen, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.platform.displayName, color = theme.textPrimary, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (account.username.isNotBlank()) "@${account.username}" else account.displayName,
                                color = theme.textTertiary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("${accountPosts.size} posts", color = theme.textPrimary, fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("Active sync", color = theme.accentNeonGreen, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsMetricBox(
    title: String,
    value: String,
    delta: String,
    modifier: Modifier = Modifier
) {
    val theme = AppTheme.colors
    Card(
        modifier = modifier.border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = theme.textTertiary, style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = theme.textPrimary, fontFamily = JetBrainsMonoFamily, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(delta, color = theme.accentNeonGreen, fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LegendItem(label: String, percent: String, color: Color) {
    val theme = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = theme.textPrimary, fontSize = 11.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(percent, color = theme.textSecondary, fontFamily = JetBrainsMonoFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Real per-range series for the analytics charts, derived from stored posts. */
private data class AnalyticsRangeData(
    val bucketLabels: List<String>,
    val cumulativeReachSeries: List<Float>,
    val impressionsSeries: List<Float>,
    /** (platform, share value); a null platform is the aggregated "Others". */
    val donutEntries: List<Pair<SocialPlatform?, Float>>,
    val donutTotal: Float
)

private fun formatCount(count: Long): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
