package com.opendroid.ai.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart-home commands. OpenDroid has NO smart-home device integration: no
 * API is contacted and no device state ever changes from these actions.
 * They therefore never report success — the honest outcome is a hand-off
 * to the Google Home app (user completes the command there), or a plain
 * "not supported" failure when even that is unavailable.
 */
@Singleton
class SmartHomeActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        GeneralSmartHomeAction(),
        ToggleLightAction(),
        SetThermostatAction(),
        LockDoorAction()
    )

    private class GeneralSmartHomeAction : Action {
        override val name: String = "SMART_HOME"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val device = params["device"] ?: "device"
            val action = params["action"] ?: "toggle"
            return handOffToGoogleHome(
                context,
                logTag = "SmartHome",
                commandDescription = "$action the $device"
            )
        }
    }

    private class ToggleLightAction : Action {
        override val name: String = "TOGGLE_LIGHT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val room = params["room"] ?: "room"
            val on = params["on"]?.toBoolean() ?: true
            val stateWord = if (on) "on" else "off"
            return handOffToGoogleHome(
                context,
                logTag = "ToggleLight",
                commandDescription = "turn the $room light $stateWord"
            )
        }
    }

    private class SetThermostatAction : Action {
        override val name: String = "SET_THERMOSTAT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val temp = params["temperature"] ?: return ActionResult(false, null, "temperature parameter is missing")
            return handOffToGoogleHome(
                context,
                logTag = "SetThermostat",
                commandDescription = "set the thermostat to $temp"
            )
        }
    }

    private class LockDoorAction : Action {
        override val name: String = "LOCK_DOOR"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val location = params["location"] ?: "front door"
            return handOffToGoogleHome(
                context,
                logTag = "LockDoor",
                commandDescription = "lock the $location"
            )
        }
    }

    companion object {
        /**
         * Opens Google Home for the user to complete [commandDescription]
         * themselves and reports a non-success user-action-required result;
         * if Google Home isn't installed, reports an honest failure.
         */
        private fun handOffToGoogleHome(
            context: Context,
            logTag: String,
            commandDescription: String
        ): ActionResult {
            return try {
                val homeIntent = context.packageManager
                    .getLaunchIntentForPackage("com.google.android.apps.chromecast.app")
                if (homeIntent != null) {
                    homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(homeIntent)
                    ActionResult.UserActionRequired(
                        "I can't control smart devices directly — no smart-home integration is set up. " +
                            "I opened Google Home; please $commandDescription there."
                    )
                } else {
                    ActionResult(
                        false, null,
                        "Smart-home control isn't supported (no device integration) and Google Home isn't installed, so I couldn't $commandDescription."
                    )
                }
            } catch (e: Exception) {
                Log.e(logTag, "Smart home hand-off failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open Google Home to $commandDescription.")
            }
        }
    }
}
