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
 * Feature B1: migration 10 → 11 adds the attachment columns to
 * `conversations`. Validated against the exported schemas in app/schemas
 * (11.json is hand-written to match this migration exactly - see
 * feature-b1-report.md; the first KSP build regenerates it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class OpenDroidDatabaseMigration10To11Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OpenDroidDatabase::class.java
    )

    private val databasePath: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath(TEST_DATABASE).absolutePath

    @Test
    fun `migration 10 to 11 adds nullable attachment columns and preserves messages`() {
        // A v10 database holding one ordinary message from before attachments.
        helper.createDatabase(databasePath, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (`id`, `text`, `sender`, `timestamp`, `sessionId`)
                VALUES ('msg-1', 'hello', 'USER', 1000, 'session-1')
                """.trimIndent()
            )
        }

        // Validates the migrated schema against the exported 11.json.
        val migrated = helper.runMigrationsAndValidate(
            databasePath, 11, true, OpenDroidDatabase.MIGRATION_10_11
        )

        // The pre-existing message survives untouched, with NULL attachment
        // columns - the upgrade alone changes nothing about old history.
        migrated.query(
            "SELECT `text`, `imageBase64`, `attachmentMimeType`, `attachmentName` " +
                "FROM conversations WHERE `id` = 'msg-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }

        // The columns are writable (the B1 send path's persistence).
        migrated.execSQL(
            """
            INSERT INTO conversations
                (`id`, `text`, `sender`, `timestamp`, `sessionId`,
                 `imageBase64`, `attachmentMimeType`, `attachmentName`)
            VALUES ('msg-2', 'what is in this image?', 'USER', 2000, 'session-1',
                    'QUJD', 'image/jpeg', 'photo.jpg')
            """.trimIndent()
        )
        migrated.query(
            "SELECT `imageBase64`, `attachmentMimeType`, `attachmentName` " +
                "FROM conversations WHERE `id` = 'msg-2'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("QUJD", cursor.getString(0))
            assertEquals("image/jpeg", cursor.getString(1))
            assertEquals("photo.jpg", cursor.getString(2))
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-test-b1"
    }
}
