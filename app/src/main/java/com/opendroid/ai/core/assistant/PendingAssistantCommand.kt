package com.opendroid.ai.core.assistant

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * C3 — hand-off slot between an assistant invocation and the chat.
 *
 * An ACTION_ASSIST launch can arrive while the app is locked (A5) or still
 * on the splash/onboarding flow, where no chat exists yet to receive the
 * command. [MainActivity] therefore parks the query here instead of
 * dispatching it directly; ChatViewModel collects [pendingQuery] and —
 * because the NavHost is only composed after the lock gate opens — the
 * command is delivered to the agent only once the chat is actually on
 * screen, through the exact same send path as typed input.
 *
 * Single-slot by design: a newer invocation replaces an undelivered older
 * one (the user re-invoking the assistant means the new command wins).
 */
@Singleton
class PendingAssistantCommand @Inject constructor() {

    private val _pendingQuery = MutableStateFlow<String?>(null)
    val pendingQuery: StateFlow<String?> = _pendingQuery.asStateFlow()

    fun offer(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            _pendingQuery.value = trimmed
        }
    }

    /** Returns the parked query and clears the slot, so each command is delivered exactly once. */
    fun consume(): String? {
        val current = _pendingQuery.value
        _pendingQuery.value = null
        return current
    }

    /**
     * Clears the slot only when it still holds [expected]; returns whether
     * it cleared. Audit-2 #11: delivery must clear the slot AFTER a
     * successful dispatch, not before — consuming first and then having the
     * send guard refuse (offline / no route) lost the user's spoken command
     * with no trace and no way to recover it.
     */
    fun consumeIf(expected: String): Boolean {
        if (_pendingQuery.value != expected) return false
        _pendingQuery.value = null
        return true
    }
}
