package com.opendroid.ai.core.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The A6 wipe relies on [ScheduledTaskStore.clearAll]: after it, no schedule
 * and no sync-dismissal record may survive — an empty store is what stops the
 * boot receiver and the sweep lane from re-arming anything.
 */
@RunWith(RobolectricTestRunner::class)
class ScheduledTaskStoreClearAllTest {

    @Test
    fun `clearAll removes tasks and dismissal bookkeeping`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ScheduledTaskStore(context)
        store.upsert(
            ScheduledTask(
                id = "task-1",
                kind = ScheduledTaskKind.INSTRUCTION,
                label = "Morning check-in",
                instruction = "Summarise my day"
            )
        )
        store.dismissSource("macro:1", "0 9 * * *")
        assertTrue(store.snapshot().isNotEmpty())
        assertTrue(store.dismissedSourceCrons().isNotEmpty())

        store.clearAll()

        assertTrue(store.snapshot().isEmpty())
        assertTrue(store.dismissedSourceCrons().isEmpty())
    }
}
