package com.opendroid.ai.accessibility

import android.util.Log
import kotlinx.coroutines.delay

object WhatsAppAutomator {

    private const val TAG = "WhatsAppAutomator"

    // Known WhatsApp chat input view IDs
    private val inputIds = listOf(
        "com.whatsapp:id/entry",
        "com.whatsapp:id/text_entry"
    )

    suspend fun automateSend(message: String): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false

        // Wait for WhatsApp chat screen to fully load
        // WhatsApp can be slow to render especially on first launch or when opening via deep link
        delay(3000)

        // Verify we're actually on a WhatsApp chat screen by checking for the input field
        if (!inputFieldPresent(service)) {
            // We may not be on the chat screen yet — wait more and retry
            delay(2000)
            if (!inputFieldPresent(service)) {
                Log.w(TAG, "WhatsApp chat input field not found — not on chat screen")
                return false
            }
        }

        // Try to type the message (in case it wasn't pre-filled by the URI)
        var typed = false
        for (id in inputIds) {
            if (service.findAndTypeById(id, message)) {
                typed = true
                break
            }
        }
        if (!typed) {
            typed = service.findAndType("Type a message", message)
        }

        // The typed flag GATES the outcome (same discipline as
        // TelegramAutomator): if the message never landed in the input
        // field, clicking a Send-looking button would send nothing (or
        // something stale) — report failure instead of hunting for it.
        // The one exception is a message the deep link already pre-filled
        // into the input: typing "failed" only because it was already
        // there, and sending it is exactly what was asked for.
        if (!typed && !inputFieldContains(service, message)) {
            Log.w(TAG, "Could not type the message into the WhatsApp input field")
            return false
        }

        delay(800)

        // WhatsApp send button IDs
        val sendButtonIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/button_send"
        )

        var sendClicked = false
        for (id in sendButtonIds) {
            if (service.findAndClickById(id)) {
                Log.d(TAG, "Successfully clicked send button by ID: $id")
                sendClicked = true
                break
            }
        }

        if (!sendClicked) {
            // Fallback to clicking Send by text / content description
            sendClicked = service.findAndClick("Send") ||
                          service.findAndClick("send") ||
                          service.findAndClick("SEND")
            if (sendClicked) {
                Log.d(TAG, "Successfully clicked send button by text label")
            }
        }

        if (!sendClicked) {
            Log.w(TAG, "Could not click send button automatically")
            return false
        }

        // Post-send verification: a clicked send button is not proof — the
        // message must have left the input field (or be visible as a
        // bubble). An input field that cannot be found proves NOTHING: an
        // empty field (never typed) is indistinguishable from a cleared
        // one, so the old "trust the click" fallback is gone — without
        // evidence the send is reported as not confirmed.
        delay(500)
        val verified = verifyMessageSent(service, message)
        if (!verified) {
            Log.w(TAG, "Send clicked but the message could not be verified as sent")
        }
        return verified
    }

    /** True when any known chat-input view id is present in the active window. */
    private fun inputFieldPresent(service: OpenDroidAccessibilityService): Boolean {
        for (id in inputIds) {
            val rootNode = service.rootInActiveWindow ?: continue
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                val found = nodes.isNotEmpty()
                nodes.forEach { it.recycle() }
                if (found) return true
            } finally {
                rootNode.recycle()
            }
        }
        return false
    }

    /** True when a visible chat input already holds [message] (deep-link pre-fill). */
    private fun inputFieldContains(service: OpenDroidAccessibilityService, message: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        try {
            for (id in inputIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    val contains = node.text?.toString()?.contains(message) == true
                    node.recycle()
                    if (contains) return true
                }
            }
            return false
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Verifies the send actually happened: if an input field is visible it
     * must no longer contain the message; if no input field is visible, the
     * message must be on screen as a sent bubble. No field and no bubble is
     * inconclusive, and inconclusive is NOT success.
     */
    private fun verifyMessageSent(service: OpenDroidAccessibilityService, message: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        try {
            var inputSeen = false
            var stillInInput = false
            for (id in inputIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    inputSeen = true
                    if (node.text?.toString()?.contains(message) == true) {
                        stillInInput = true
                    }
                    node.recycle()
                }
            }
            if (stillInInput) return false
            if (inputSeen) return true
            return AccessibilityNodeTraversal().screenText(rootNode).contains(message)
        } finally {
            rootNode.recycle()
        }
    }
}
