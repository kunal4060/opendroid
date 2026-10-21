package com.opendroid.ai.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantRolePolicyTest {

    @Test
    fun `pre-API-29 devices are unsupported regardless of role facts`() {
        assertEquals(
            AssistantRoleStatus.UNSUPPORTED,
            AssistantRolePolicy.statusFor(sdkInt = 28, roleAvailable = true, roleHeld = true)
        )
        assertEquals(
            AssistantRoleStatus.UNSUPPORTED,
            AssistantRolePolicy.statusFor(sdkInt = 26, roleAvailable = false, roleHeld = false)
        )
    }

    @Test
    fun `held role wins over availability`() {
        assertEquals(
            AssistantRoleStatus.HELD,
            AssistantRolePolicy.statusFor(sdkInt = 34, roleAvailable = true, roleHeld = true)
        )
    }

    @Test
    fun `available but not held is NOT_HELD`() {
        assertEquals(
            AssistantRoleStatus.NOT_HELD,
            AssistantRolePolicy.statusFor(sdkInt = 29, roleAvailable = true, roleHeld = false)
        )
    }

    @Test
    fun `unavailable role is reported separately so UI can deep-link instead`() {
        assertEquals(
            AssistantRoleStatus.ROLE_UNAVAILABLE,
            AssistantRolePolicy.statusFor(sdkInt = 34, roleAvailable = false, roleHeld = false)
        )
    }

    @Test
    fun `actions follow the status table`() {
        assertEquals(AssistantRoleAction.NONE, AssistantRolePolicy.actionFor(AssistantRoleStatus.HELD))
        assertEquals(
            AssistantRoleAction.REQUEST_ROLE,
            AssistantRolePolicy.actionFor(AssistantRoleStatus.NOT_HELD)
        )
        assertEquals(
            AssistantRoleAction.OPEN_SETTINGS,
            AssistantRolePolicy.actionFor(AssistantRoleStatus.ROLE_UNAVAILABLE)
        )
        assertEquals(
            AssistantRoleAction.OPEN_SETTINGS,
            AssistantRolePolicy.actionFor(AssistantRoleStatus.UNSUPPORTED)
        )
    }

    @Test
    fun `every status has a non-blank honest label`() {
        AssistantRoleStatus.entries.forEach { status ->
            assert(AssistantRolePolicy.statusLabel(status).isNotBlank())
        }
    }
}
