package com.opendroid.ai.actions

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionRisk
import com.opendroid.ai.core.agent.ActionSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartHomeActionsTest {

    private val baseContext = ApplicationProvider.getApplicationContext<Context>()
    private val actions = SmartHomeActions().getActions().associateBy { it.name }

    @Test
    fun `smart home actions are all registered`() {
        assertEquals(
            setOf("SMART_HOME", "TOGGLE_LIGHT", "SET_THERMOSTAT", "LOCK_DOOR"),
            actions.keys
        )
    }

    @Test
    fun `general smart home opens Google Home when installed`() = runBlocking {
        installGoogleHomeLauncher()
        val context = RecordingContext(baseContext)

        val result = execute(
            "SMART_HOME",
            mapOf("device" to "living room fan", "action" to "toggle", "value" to "on"),
            context
        )

        // No device is controlled: the command is handed off to the user
        // inside Google Home, so the result is non-success.
        assertTrue(result is ActionResult.UserActionRequired)
        assertFalse(result.success)
        assertNull(result.data)
        assertEquals(
            "I can't control smart devices directly \u2014 no smart-home integration is set up. " +
                "I opened Google Home; please toggle the living room fan there.",
            result.error
        )
        assertEquals(
            GOOGLE_HOME_PACKAGE,
            context.lastStartedIntent?.component?.packageName
        )
    }

    @Test
    fun `general smart home reports unsupported failure when Google Home is not installed`() = runBlocking {
        val context = RecordingContext(baseContext)

        val result = execute(
            "SMART_HOME",
            mapOf("device" to "kitchen light", "action" to "off", "value" to "now"),
            context
        )

        assertTrue(result is ActionResult.Failure)
        assertFalse(result.success)
        assertEquals(
            "Smart-home control isn't supported (no device integration) and Google Home isn't installed, " +
                "so I couldn't off the kitchen light.",
            result.error
        )
        assertNull(context.lastStartedIntent)
    }

    @Test
    fun `toggle light reports unsupported failure for on and off when Google Home is not installed`() = runBlocking {
        val context = RecordingContext(baseContext)

        val onResult = execute(
            "TOGGLE_LIGHT",
            mapOf(
                "room" to "bedroom",
                "on" to "true",
                "brightness" to "40",
                "color" to "blue"
            ),
            context
        )

        assertTrue(onResult is ActionResult.Failure)
        assertFalse(onResult.success)
        assertEquals(
            "Smart-home control isn't supported (no device integration) and Google Home isn't installed, " +
                "so I couldn't turn the bedroom light on.",
            onResult.error
        )
        assertNull(context.lastStartedIntent)

        val offResult = execute(
            "TOGGLE_LIGHT",
            mapOf("room" to "hallway", "on" to "false"),
            context
        )

        assertTrue(offResult is ActionResult.Failure)
        assertFalse(offResult.success)
        assertEquals(
            "Smart-home control isn't supported (no device integration) and Google Home isn't installed, " +
                "so I couldn't turn the hallway light off.",
            offResult.error
        )
        assertNull(context.lastStartedIntent)
    }

    @Test
    fun `set thermostat reports missing temperature without launching`() = runBlocking {
        val context = RecordingContext(baseContext)

        val result = execute("SET_THERMOSTAT", emptyMap(), context)

        assertTrue(result is ActionResult.Failure)
        assertFalse(result.success)
        assertEquals("temperature parameter is missing", result.error)
        assertNull(context.lastStartedIntent)
    }

    @Test
    fun `set thermostat reports unsupported failure when Google Home is not installed`() = runBlocking {
        val context = RecordingContext(baseContext)

        val result = execute("SET_THERMOSTAT", mapOf("temperature" to "22"), context)

        assertTrue(result is ActionResult.Failure)
        assertFalse(result.success)
        assertEquals(
            "Smart-home control isn't supported (no device integration) and Google Home isn't installed, " +
                "so I couldn't set the thermostat to 22.",
            result.error
        )
        assertNull(context.lastStartedIntent)
    }

    @Test
    fun `lock door reports unsupported failure for the default and an explicit location`() = runBlocking {
        val context = RecordingContext(baseContext)

        val defaultResult = execute("LOCK_DOOR", emptyMap(), context)

        assertTrue(defaultResult is ActionResult.Failure)
        assertFalse(defaultResult.success)
        assertEquals(
            "Smart-home control isn't supported (no device integration) and Google Home isn't installed, " +
                "so I couldn't lock the front door.",
            defaultResult.error
        )
        assertNull(context.lastStartedIntent)

        val explicitResult = execute(
            "LOCK_DOOR",
            mapOf("location" to "garage door"),
            context
        )

        assertTrue(explicitResult is ActionResult.Failure)
        assertFalse(explicitResult.success)
        assertEquals(
            "Smart-home control isn't supported (no device integration) and Google Home isn't installed, " +
                "so I couldn't lock the garage door.",
            explicitResult.error
        )
        assertNull(context.lastStartedIntent)
    }

    @Test
    fun `lock door reports a truthful failure when launching fails`() = runBlocking {
        // Google Home must be installed for the hand-off launch to be
        // attempted at all; the recording context then makes it throw.
        installGoogleHomeLauncher()
        val context = RecordingContext(
            baseContext,
            IllegalStateException("launcher unavailable")
        )

        val result = execute("LOCK_DOOR", mapOf("location" to "front door"), context)

        assertTrue(result is ActionResult.Failure)
        assertFalse(result.success)
        assertEquals("Couldn't open Google Home to lock the front door.", result.error)
        assertNull(context.lastStartedIntent)
    }

    @Test
    fun `lock door remains behind the irreversible approval boundary`() {
        val definition = ActionSchema.getAction("LOCK_DOOR")

        assertNotNull(definition)
        assertTrue(definition!!.neverAutoApprove)
        assertEquals(ActionRisk.IRREVERSIBLE, ActionSchema.riskForAction("LOCK_DOOR"))
    }

    private suspend fun execute(
        actionName: String,
        params: Map<String, String>,
        context: Context
    ): ActionResult = actions.getValue(actionName).execute(params, context)

    @Suppress("DEPRECATION")
    private fun installGoogleHomeLauncher() {
        val component = ComponentName(GOOGLE_HOME_PACKAGE, "$GOOGLE_HOME_PACKAGE.MainActivity")
        val packageManager = shadowOf(baseContext.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )
    }

    private class RecordingContext(
        base: Context,
        private val startActivityFailure: Exception? = null
    ) : ContextWrapper(base) {
        val startedIntents = mutableListOf<Intent>()
        val lastStartedIntent: Intent?
            get() = startedIntents.lastOrNull()

        override fun startActivity(intent: Intent) {
            startActivityFailure?.let { throw it }
            startedIntents += intent
        }
    }

    private companion object {
        const val GOOGLE_HOME_PACKAGE = "com.google.android.apps.chromecast.app"
    }
}
