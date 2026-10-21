package com.opendroid.ai.accessibility

import android.util.Log
import kotlinx.coroutines.delay

object TelegramAutomator {

    private const val TAG = "TelegramAutomator"

    // Known Telegram chat input view IDs
    private val inputIds = listOf(
        "org.telegram.messenger:id/chat_text_edit",
        "org.telegram.messenger.web:id/chat_text_edit",
        "org.telegram.plus:id/chat_text_edit",
        "chat_text_edit",
        "chat_message_text"
    )

    suspend fun automateSend(message: String): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false

        // Wait for Telegram chat screen to render
        delay(2500)

        if (!inputFieldPresent(service)) {
            delay(1500)
            inputFieldPresent(service)
        }

        // Type the message into the input field
        var typed = false
        for (id in inputIds) {
            if (service.findAndTypeById(id, message)) {
                typed = true
                break
            }
        }
        if (!typed) {
            typed = service.findAndType("Message", message) ||
                    service.findAndType("Write a message...", message)
        }

        // The typed flag GATES the outcome: if the message never landed in
        // any input field, clicking a Send-looking button would send nothing
        // (or something stale) — report failure instead of hunting for it.
        if (!typed) {
            Log.w(TAG, "Could not type the message into any Telegram input field")
            return false
        }

        delay(600)

        // Known Telegram send button view IDs
        val sendButtonIds = listOf(
            "org.telegram.messenger:id/send_button",
            "org.telegram.messenger.web:id/send_button",
            "org.telegram.plus:id/send_button",
            "chat_send_button",
            "send_button"
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
            sendClicked = service.findAndClick("Send") ||
                          service.findAndClick("Send message") ||
                          service.findAndClick("send")
            if (sendClicked) {
                Log.d(TAG, "Successfully clicked send button by text label")
            }
        }

        if (!sendClicked) {
            Log.w(TAG, "Could not automatically click Telegram send button")
            return false
        }

        // Post-send verification: a clicked send button is not proof — the
        // message must have left the input field (or be visible as a bubble).
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

    /**
     * Verifies the send actually happened: if an input field is visible it
     * must no longer contain the message; if no input field is visible, the
     * message must be on screen as a sent bubble.
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
