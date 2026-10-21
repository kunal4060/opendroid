package com.opendroid.ai.core.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The A6 wipe relies on [WidgetEventRecorder.clearAll]: the briefing ledger
 * is small, but a "forget everything" report may not leave it behind.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetEventRecorderClearAllTest {

    @Test
    fun `clearAll erases the recorded briefing stamp`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WidgetEventRecorder.recordBriefingGenerated(context)
        assertNotNull(WidgetEventRecorder.lastBriefingAt(context).first())

        WidgetEventRecorder.clearAll(context)

        assertNull(WidgetEventRecorder.lastBriefingAt(context).first())
    }
}
