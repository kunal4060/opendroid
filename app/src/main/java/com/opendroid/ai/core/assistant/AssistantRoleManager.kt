package com.opendroid.ai.core.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * C3 — framework-facing half of the default-assistant role integration.
 *
 * Reads the live role state through [RoleManager] (API 29+) and builds the
 * intents the Settings UI launches: the system role-request dialog first,
 * and — when the role is unavailable, denied, or the device predates
 * RoleManager — a deep link into the system screen where the default
 * assistant is chosen manually, so the user is never left at a dead end.
 *
 * Plain class (not Hilt-managed): the Settings section and any future
 * onboarding card can each construct one from their context cheaply; all
 * state lives in the platform, not here.
 */
class AssistantRoleManager(context: Context) {

    private val appContext: Context = context.applicationContext

    fun currentStatus(): AssistantRoleStatus {
        if (Build.VERSION.SDK_INT < AssistantRolePolicy.MIN_SDK_FOR_ROLE) {
            return AssistantRolePolicy.statusFor(
                sdkInt = Build.VERSION.SDK_INT,
                roleAvailable = false,
                roleHeld = false
            )
        }
        val roleManager = appContext.getSystemService(RoleManager::class.java)
            ?: return AssistantRoleStatus.ROLE_UNAVAILABLE
        return AssistantRolePolicy.statusFor(
            sdkInt = Build.VERSION.SDK_INT,
            roleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT),
            roleHeld = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        )
    }

    /**
     * Intent for the system "set default assistant" dialog, or null when
     * the role cannot be requested on this device (caller falls back to
     * [createFallbackSettingsIntent]).
     */
    fun createRequestRoleIntent(): Intent? {
        if (Build.VERSION.SDK_INT < AssistantRolePolicy.MIN_SDK_FOR_ROLE) return null
        val roleManager = appContext.getSystemService(RoleManager::class.java) ?: return null
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
        if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return null
        return try {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        } catch (e: RuntimeException) {
            // Some OEM builds throw instead of showing the dialog; the
            // settings deep link is the documented fallback path.
            null
        }
    }

    /**
     * Deep link to the system screen where the default assistant is set.
     * Tries, in order: voice-input settings (hosts the "Assist app" picker
     * on most builds), the default-apps settings screen, then top-level
     * Settings — whichever this device actually resolves.
     */
    fun createFallbackSettingsIntent(): Intent {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        val resolved = candidates.firstOrNull { candidate ->
            appContext.packageManager.resolveActivity(candidate, 0) != null
        } ?: candidates.last()
        return resolved.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
