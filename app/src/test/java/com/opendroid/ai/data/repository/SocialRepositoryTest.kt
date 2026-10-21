package com.opendroid.ai.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.entities.SocialAccountEntity
import com.opendroid.ai.data.db.entities.SocialCommentEntity
import com.opendroid.ai.social.domain.model.SocialAccount
import com.opendroid.ai.social.domain.model.SocialComment
import com.opendroid.ai.social.domain.model.SocialPlatform
import com.opendroid.ai.social.domain.model.SocialPost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class SocialRepositoryTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var repository: SocialRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SocialRepository(
            database.socialAccountDao(),
            database.socialPostDao(),
            database.socialCommentDao(),
            database.socialInteractionDao(),
            database.socialAnalyticsDao(),
            database.socialCampaignDao(),
            database.socialAutomationRuleDao(),
            database.socialAuditLogDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun accountEntity(id: String, platform: String) = SocialAccountEntity(
        id = id,
        platform = platform,
        username = "user-$id",
        displayName = "User $id",
        avatarUrl = null,
        status = "CONNECTED",
        permissionsJson = "[]",
        connectedAt = 0L,
        tokenExpiresAt = null,
        lastSyncAt = null,
        errorMessage = null
    )

    private fun commentEntity(id: String, postId: String?, platform: String) = SocialCommentEntity(
        id = id,
        platformCommentId = "pc-$id",
        postId = postId,
        platform = platform,
        authorName = "Alex",
        authorAvatarUrl = null,
        content = "hello",
        timestamp = 0L,
        isAnswered = false,
        suggestedReply = null,
        actualReply = null,
        replyStatus = "NONE",
        replyTimestamp = null,
        sentiment = "NEUTRAL",
        category = "GENERAL",
        priority = "NORMAL"
    )

    private fun account(id: String) = SocialAccount(
        id = id,
        platform = SocialPlatform.X,
        username = "user-$id",
        displayName = "User $id"
    )

    private fun post(id: String, accountId: String, platformPostId: String) = SocialPost(
        id = id,
        accountId = accountId,
        platform = SocialPlatform.X,
        content = "post $id",
        platformPostId = platformPostId
    )

    private fun comment(id: String, postId: String?) = SocialComment(
        id = id,
        platformCommentId = "pc-$id",
        postId = postId,
        platform = SocialPlatform.X,
        authorName = "Alex",
        content = "comment $id"
    )

    @Test
    fun `account row with an unknown platform is skipped instead of crashing the flow`() = runBlocking {
        database.socialAccountDao().insertOrUpdate(accountEntity(id = "poisoned", platform = "MYSPACE"))
        repository.saveAccount(account(id = "good"))

        val accounts = repository.getAllAccountsFlow().first()
        assertEquals(listOf("good"), accounts.map { it.id })
        assertNull(repository.getAccountById("poisoned"))
    }

    @Test
    fun `comment row with an unknown platform is skipped instead of crashing the flow`() = runBlocking {
        database.socialCommentDao().insertOrUpdate(commentEntity(id = "poisoned", postId = null, platform = "MYSPACE"))
        repository.saveComment(comment(id = "good", postId = null))

        val comments = repository.getAllCommentsFlow().first()
        assertEquals(listOf("good"), comments.map { it.id })
    }

    @Test
    fun `deleting a post deletes its comments`() = runBlocking {
        repository.savePost(post(id = "post-1", accountId = "acc-1", platformPostId = "plat-1"))
        repository.savePost(post(id = "post-2", accountId = "acc-1", platformPostId = "plat-2"))
        repository.saveComment(comment(id = "c1", postId = "plat-1"))
        repository.saveComment(comment(id = "c2", postId = "plat-2"))

        repository.deletePost("post-1")

        assertNull(repository.getPostById("post-1"))
        val remaining = repository.getAllCommentsFlow().first().map { it.id }
        assertEquals(listOf("c2"), remaining)
    }

    @Test
    fun `deleting an account deletes its posts and their comments only`() = runBlocking {
        repository.saveAccount(account(id = "acc-a"))
        repository.saveAccount(account(id = "acc-b"))
        repository.savePost(post(id = "post-a", accountId = "acc-a", platformPostId = "plat-a"))
        repository.savePost(post(id = "post-b", accountId = "acc-b", platformPostId = "plat-b"))
        repository.saveComment(comment(id = "ca", postId = "plat-a"))
        repository.saveComment(comment(id = "cb", postId = "plat-b"))
        // Unattributable in this schema (null postId) — survives by design;
        // see the residual note on SocialRepository.deleteAccount.
        repository.saveComment(comment(id = "corphan", postId = null))

        repository.deleteAccount("acc-a")

        assertNull(repository.getAccountById("acc-a"))
        assertNull(repository.getPostById("post-a"))
        assertNotNull(repository.getAccountById("acc-b"))
        assertNotNull(repository.getPostById("post-b"))
        val remaining = repository.getAllCommentsFlow().first().map { it.id }
        assertEquals(setOf("cb", "corphan"), remaining.toSet())
    }
}
