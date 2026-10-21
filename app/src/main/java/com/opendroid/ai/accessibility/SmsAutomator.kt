package com.opendroid.ai.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

object SmsAutomator {

    private const val TAG = "SmsAutomator"

    // Known SMS compose-field view IDs (the field the smsto: intent pre-fills)
    private val composeFieldIds = listOf(
        "com.google.android.apps.messaging:id/compose_message_text",
        "com.samsung.android.messaging:id/message_text",
        "com.android.mms:id/embedded_text_editor",
        "com.android.messaging:id/compose_message_text"
    )

    // Common SMS app send button IDs
    private val sendButtonIds = listOf(
        "com.google.android.apps.messaging:id/send_message_button",
        "com.google.android.apps.messaging:id/send_message_button_icon",
        "com.samsung.android.messaging:id/send_button",
        "com.android.mms:id/send_button",
        "com.google.android.apps.messaging:id/send_button",
        "com.android.messaging:id/send_message_button"
    )

    suspend fun automateSend(message: String): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false

        // Wait for screen transition
        delay(1500)

        // Gate (same discipline as Telegram/WhatsApp): the body was
        // pre-filled by the smsto: intent, so before hunting for a send
        // button, prove the compose field actually holds THIS message.
        // If it doesn't, clicking "send" would dispatch nothing — or a
        // stale draft — and reporting success would be a lie.
        if (!composeFieldContains(service, message)) {
            Log.w(TAG, "Compose field does not hold the message — refusing to tap send")
            return false
        }

        var sendClicked = false
        for (id in sendButtonIds) {
            if (service.findAndClickById(id)) {
                Log.d(TAG, "Successfully clicked SMS send button by ID: $id")
                sendClicked = true
                break
            }
        }

        if (!sendClicked) {
            // Clicking by text is restricted to actual send labels. The old
            // "SMS" fallback matched ANY node containing "SMS" (a tab, a
            // conversation label, a chip) and reported it as a sent text.
            sendClicked = service.findAndClick("Send") ||
                          service.findAndClick("send") ||
                          service.findAndClick("SEND")
            if (sendClicked) {
                Log.d(TAG, "Successfully clicked SMS send button by text label")
            }
        }

        if (!sendClicked) {
            Log.w(TAG, "Could not click SMS send button automatically")
            return false
        }

        // Post-send verification: a clicked send button is not proof — the
        // message must have left the compose field (or be visible as a
        // sent bubble). No field and no bubble is inconclusive, and
        // inconclusive is NOT success.
        delay(500)
        val verified = verifyMessageSent(service, message)
        if (!verified) {
            Log.w(TAG, "Send clicked but the message could not be verified as sent")
        }
        return verified
    }

    /**
     * True when the compose field holds [message]. Looks the field up by
     * known view IDs first, then by any editable node in the window (OEM
     * messaging apps vary). No compose field at all → false: there is no
     * evidence the message on screen is the one that would be sent.
     */
    private fun composeFieldContains(service: OpenDroidAccessibilityService, message: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        try {
            for (id in composeFieldIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    val contains = node.text?.toString()?.contains(message) == true
                    node.recycle()
                    if (contains) return true
                }
            }
            val editable = findEditableNode(rootNode)
            return editable?.text?.toString()?.contains(message) == true
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Verifies the send actually happened: if a compose field is visible
     * it must no longer contain the message; if no compose field is
     * visible, the message must be on screen as a sent bubble.
     */
    private fun verifyMessageSent(service: OpenDroidAccessibilityService, message: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        try {
            var fieldSeen = false
            var stillInField = false
            for (id in composeFieldIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    fieldSeen = true
                    if (node.text?.toString()?.contains(message) == true) {
                        stillInField = true
                    }
                    node.recycle()
                }
            }
            val editable = findEditableNode(rootNode)
            if (editable != null) {
                fieldSeen = true
                if (editable.text?.toString()?.contains(message) == true) {
                    stillInField = true
                }
            }
            if (stillInField) return false
            if (fieldSeen) return true
            return AccessibilityNodeTraversal().screenText(rootNode).contains(message)
        } finally {
            rootNode.recycle()
        }
    }

    /** Depth-first search for the first editable node in the window. */
    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }
}
