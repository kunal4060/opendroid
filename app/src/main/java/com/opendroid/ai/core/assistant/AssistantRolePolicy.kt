package com.opendroid.ai.core.assistant

/**
 * C3 — default-assistant role: pure decision logic.
 *
 * Split from [AssistantRoleManager] (which talks to the framework) so the
 * whole status/action decision table can be unit-tested on the JVM without
 * a device. The manager feeds it three facts — SDK level, whether the
 * platform offers ROLE_ASSISTANT, whether OpenDroid already holds it — and
 * this object decides what the user should be told and offered.
 */

/** What the assistant role looks like from the user's point of view. */
enum class AssistantRoleStatus {
    /** Device runs Android 9 or lower: RoleManager does not exist there. */
    UNSUPPORTED,

    /** RoleManager exists but reports ROLE_ASSISTANT as unavailable (rare OEM builds). */
    ROLE_UNAVAILABLE,

    /** The role exists and another app (or nobody) holds it. */
    NOT_HELD,

    /** OpenDroid is the device's default assistant. */
    HELD
}

/** The one action the Settings UI should offer for a given status. */
enum class AssistantRoleAction {
    /** Fire the system role-request dialog (RoleManager.createRequestRoleIntent). */
    REQUEST_ROLE,

    /**
     * Deep-link to a system settings screen where the assistant can be
     * picked manually — the honest fallback when the role dialog cannot be
     * used, so denial/unavailability never fails silently (checklist C3).
     */
    OPEN_SETTINGS,

    /** Already held: nothing to ask for. */
    NONE
}

object AssistantRolePolicy {

    /** RoleManager (and ROLE_ASSISTANT) arrived in Android 10 / API 29. */
    const val MIN_SDK_FOR_ROLE: Int = 29

    fun statusFor(
        sdkInt: Int,
        roleAvailable: Boolean,
        roleHeld: Boolean
    ): AssistantRoleStatus = when {
        sdkInt < MIN_SDK_FOR_ROLE -> AssistantRoleStatus.UNSUPPORTED
        roleHeld -> AssistantRoleStatus.HELD
        !roleAvailable -> AssistantRoleStatus.ROLE_UNAVAILABLE
        else -> AssistantRoleStatus.NOT_HELD
    }

    fun actionFor(status: AssistantRoleStatus): AssistantRoleAction = when (status) {
        AssistantRoleStatus.HELD -> AssistantRoleAction.NONE
        AssistantRoleStatus.NOT_HELD -> AssistantRoleAction.REQUEST_ROLE
        AssistantRoleStatus.ROLE_UNAVAILABLE -> AssistantRoleAction.OPEN_SETTINGS
        AssistantRoleStatus.UNSUPPORTED -> AssistantRoleAction.OPEN_SETTINGS
    }

    /** Short status line shown in Settings; wording is honest about limits. */
    fun statusLabel(status: AssistantRoleStatus): String = when (status) {
        AssistantRoleStatus.HELD -> "OpenDroid is your default assistant"
        AssistantRoleStatus.NOT_HELD -> "Another app is the default assistant"
        AssistantRoleStatus.ROLE_UNAVAILABLE ->
            "This device does not offer the assistant role — pick OpenDroid in system settings"
        AssistantRoleStatus.UNSUPPORTED ->
            "Needs Android 10 or newer — pick OpenDroid as the assist app in system settings"
    }
}
