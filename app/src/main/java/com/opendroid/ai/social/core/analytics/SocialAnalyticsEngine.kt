package com.opendroid.ai.social.core.analytics

import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

data class AnalyticsSummary(
    val totalFollowers: Int,
    val totalReach: Int,
    val totalImpressions: Int,
    val averageEngagementRate: Float,
    val totalPosts: Int,
    val followersGrowthDelta: Int,
    val reachGrowthDelta: Int,
    val periodLabel: String
)

@Singleton
class SocialAnalyticsEngine @Inject constructor(
    private val socialRepository: SocialRepository
) {

    suspend fun getSummary(rangeDays: Int = 7): AnalyticsSummary = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val currentPeriodStart = now - (rangeDays * 86400_000L)
        val previousPeriodStart = currentPeriodStart - (rangeDays * 86400_000L)

        val currentSnapshots = socialRepository.getSnapshotsBetween(currentPeriodStart, now)
        val prevSnapshots = socialRepository.getSnapshotsBetween(previousPeriodStart, currentPeriodStart)
        val publishedPosts = socialRepository.getPostsByStatus(PostStatus.PUBLISHED)
            .filter { (it.publishedTime ?: it.createdAt) >= currentPeriodStart }

        val totalFollowers = currentSnapshots.groupBy { it.platform }
            .values.mapNotNull { list -> list.maxByOrNull { it.timestamp }?.followersCount }
            .sum()

        val prevFollowers = prevSnapshots.groupBy { it.platform }
            .values.mapNotNull { list -> list.maxByOrNull { it.timestamp }?.followersCount }
            .sum()

        // Reach/impressions are point-in-time account metrics, exactly
        // like followers above: one snapshot row is stored per sync, so
        // summing every snapshot in the window would multiply the true
        // figure by the number of syncs. Take the LATEST snapshot per
        // platform, as the followers computation already does.
        val totalReach = currentSnapshots.groupBy { it.platform }
            .values.mapNotNull { list -> list.maxByOrNull { it.timestamp }?.reach }
            .sum()
        val prevReach = prevSnapshots.groupBy { it.platform }
            .values.mapNotNull { list -> list.maxByOrNull { it.timestamp }?.reach }
            .sum()
        val totalImpressions = currentSnapshots.groupBy { it.platform }
            .values.mapNotNull { list -> list.maxByOrNull { it.timestamp }?.impressions }
            .sum()

        val nonZeroEngagements = currentSnapshots.mapNotNull { it.engagementRate }.filter { it > 0f }
        val avgEngagement = if (nonZeroEngagements.isNotEmpty()) nonZeroEngagements.average().toFloat() else 0f

        AnalyticsSummary(
            totalFollowers = totalFollowers,
            totalReach = totalReach,
            totalImpressions = totalImpressions,
            averageEngagementRate = avgEngagement,
            totalPosts = publishedPosts.size,
            followersGrowthDelta = totalFollowers - prevFollowers,
            reachGrowthDelta = totalReach - prevReach,
            periodLabel = "Last $rangeDays Days"
        )
    }

    suspend fun generateWeeklyReport(): SocialReport = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - 7 * 86400_000L

        val summary = getSummary(7)
        // Every figure below is derived from THIS week's data only.
        val weekPosts = socialRepository.getPostsByStatus(PostStatus.PUBLISHED)
            .filter { (it.publishedTime ?: it.createdAt) >= sevenDaysAgo }
        val allComments = socialRepository.getAllCommentsFlow().first()

        // Comments RECEIVED this week (stored comments timestamped in
        // the window) and replies actually SENT this week (comments
        // whose reply was sent in the window, whenever the comment
        // itself arrived) are two different numbers from two different
        // sources — the previous implementation reported one all-time
        // comments-received sum as both.
        val commentsReceived = allComments.count { it.timestamp >= sevenDaysAgo }
        val repliesSent = allComments.count { comment ->
            val repliedAt = comment.replyTimestamp
            repliedAt != null && repliedAt >= sevenDaysAgo
        }

        // This week's top post, scored with the same formula the DAO's
        // getTopPerformingPosts uses. With no posts this week there is
        // no top post: both fields stay null and consumers report
        // insufficient data instead of an invented platform/post.
        val topPost = weekPosts.maxByOrNull { it.likesCount + it.commentsCount * 2 + it.sharesCount * 3 }
        val topPlatform = topPost?.platform
        val topPostSummary = topPost?.content?.take(80)

        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        val periodTitle = "${dateFormat.format(Date(sevenDaysAgo))} – ${dateFormat.format(Date(now))}"

        val aiSummary = if (summary.totalPosts > 0) {
            "Your product and developer updates generated the strongest engagement this week with ${summary.totalReach} total reach and an average engagement rate of ${String.format("%.1f", summary.averageEngagementRate)}%."
        } else {
            "Not enough data for a weekly summary: no posts were published in this period. Publishing regular updates and release announcements will increase reach."
        }

        val recommendations = listOf(
            "Schedule mid-week technical deep dives on X and LinkedIn to capitalize on highest developer engagement.",
            "Utilize community polls on Telegram and Discord to gather immediate user feedback for OpenDroid updates.",
            "Continue pairing product announcements with visual demo clips to optimize reach on Instagram and YouTube."
        )

        SocialReport(
            id = UUID.randomUUID().toString(),
            periodTitle = periodTitle,
            startDate = sevenDaysAgo,
            endDate = now,
            totalFollowersDelta = summary.followersGrowthDelta,
            totalReach = summary.totalReach,
            totalImpressions = summary.totalImpressions,
            averageEngagementRate = summary.averageEngagementRate,
            totalPostsPublished = summary.totalPosts,
            totalCommentsReceived = commentsReceived,
            totalRepliesSent = repliesSent,
            topPlatform = topPlatform,
            topPostSummary = topPostSummary,
            aiSummary = aiSummary,
            recommendations = recommendations,
            generatedAt = now
        )
    }
}
