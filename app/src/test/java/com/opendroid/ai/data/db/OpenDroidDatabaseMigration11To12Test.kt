package com.opendroid.ai.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feature D3: migration 11 → 12 adds the research citation tables
 * (`research_sources`, `research_runs`). Purely additive — existing chat
 * history must survive untouched. Validated against the exported schemas
 * in app/schemas (12.json is hand-written to match this migration exactly,
 * the same approach B1 used for 11.json — the first KSP build regenerates
 * it; see feature-d3-report.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class OpenDroidDatabaseMigration11To12Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OpenDroidDatabase::class.java
    )

    private val databasePath: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath(TEST_DATABASE).absolutePath

    @Test
    fun `migration 11 to 12 creates the research tables and preserves messages`() {
        // A v11 database holding one ordinary message from before research.
        helper.createDatabase(databasePath, 11).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (`id`, `text`, `sender`, `timestamp`, `sessionId`)
                VALUES ('msg-1', 'hello', 'USER', 1000, 'session-1')
                """.trimIndent()
            )
        }

        // Validates the migrated schema against the exported 12.json.
        val migrated = helper.runMigrationsAndValidate(
            databasePath, 12, true, OpenDroidDatabase.MIGRATION_11_12
        )

        // The pre-existing message survives untouched.
        migrated.query(
            "SELECT `text` FROM conversations WHERE `id` = 'msg-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
        }

        // Both research tables exist and start empty.
        migrated.query("SELECT COUNT(*) FROM research_sources").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM research_runs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        // The tables are writable in the shape the pipeline writes: a
        // source row under a message id, and its run row.
        migrated.execSQL(
            """
            INSERT INTO research_sources
                (`messageId`, `position`, `url`, `title`, `snippet`, `fetchedAt`, `contentHash`)
            VALUES ('msg-2', 1, 'https://example.org/a', 'Example A', 'snippet', 2000, 'abc123')
            """.trimIndent()
        )
        migrated.execSQL(
            """
            INSERT INTO research_runs
                (`runId`, `sessionId`, `messageId`, `query`, `answerText`, `createdAt`)
            VALUES ('run-1', 'session-1', 'msg-2', 'a question', 'An answer [1].', 2000)
            """.trimIndent()
        )
        migrated.query(
            "SELECT `position`, `url` FROM research_sources WHERE `messageId` = 'msg-2'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("https://example.org/a", cursor.getString(1))
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-test-d3"
    }
}
