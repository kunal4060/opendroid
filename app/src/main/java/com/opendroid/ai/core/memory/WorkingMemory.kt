package com.opendroid.ai.core.memory

import android.content.Context
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Plan
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkingMemory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceStateProvider: DeviceStateProvider
) {
    private val historyLock = Any()
    private val _conversationHistory = mutableListOf<ChatMessage>()

    /**
     * A snapshot copy of the recent conversation. The backing list used to
     * be handed out directly while agent coroutines mutated it —
     * iterating it during composition could throw
     * ConcurrentModificationException or lose updates.
     */
    val conversationHistory: List<ChatMessage>
        get() = synchronized(historyLock) { _conversationHistory.toList() }

    var activePlan: Plan? = null
    var location: String = "Unknown"

    val batteryLevel: Int
        get() = deviceStateProvider.getBatteryLevel()

    val wifiState: String
        get() = deviceStateProvider.getWifiState()

    val connectivity: String
        get() = deviceStateProvider.getConnectivityState()

    val isInternetAvailable: Boolean
        get() = deviceStateProvider.isInternetAvailable()

    val locationContext: String
        get() {
            val loc = deviceStateProvider.getLocationContext()
            location = loc // keep location field in sync
            return loc
        }

    fun addMessage(msg: ChatMessage) {
        synchronized(historyLock) {
            _conversationHistory.add(msg)
            if (_conversationHistory.size > 20) {
                _conversationHistory.removeAt(0)
            }
        }
    }

    fun clear() {
        synchronized(historyLock) { _conversationHistory.clear() }
        activePlan = null
    }
}
