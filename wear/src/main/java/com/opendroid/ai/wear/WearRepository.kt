package com.opendroid.ai.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

/** UI state for one in-flight (or just-finished) watch command. */
sealed interface WearCommandState {
    data object Idle : WearCommandState
    data class Sending(val correlationId: String, val text: String) : WearCommandState
    data class Result(val outcome: String, val message: String) : WearCommandState

    /** No result within the wait window — shown honestly, never as success. */
    data class TimedOut(val text: String) : WearCommandState
}

/**
 * The watch's whole Data Layer client, in one small class (the watch is a
 * thin client by design — all agent intelligence stays on the phone):
 *
 *  - Reachability comes from CapabilityClient: only a node advertising
 *    [WearProtocol.CAPABILITY_AGENT] counts as "the phone", and the
 *    honest disconnected state renders whenever none is reachable.
 *  - Status and pending-approval are DataClient items published by the
 *    phone; this class only listens (it never writes DataItems).
 *  - Commands and approval decisions are MessageClient sends correlated
 *    by id; a command result is matched strictly by correlation id, and a
 *    result that never arrives becomes [WearCommandState.TimedOut] —
 *    never a fake success.
 *
 * Listeners are registered for the Activity's lifetime ([start]/[stop]).
 */
class WearRepository(context: Context) :
    DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener,
    CapabilityClient.OnCapabilityChangedListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = WearJson.instance

    private val dataClient by lazy { Wearable.getDataClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }

    private val _phoneReachable = MutableStateFlow(false)
    val phoneReachable: StateFlow<Boolean> = _phoneReachable.asStateFlow()

    private val _phoneNodeId = MutableStateFlow<String?>(null)

    private val _status = MutableStateFlow<WearStatusSnapshot?>(null)
    val status: StateFlow<WearStatusSnapshot?> = _status.asStateFlow()

    private val _pendingApproval = MutableStateFlow<WearPendingApproval?>(null)
    val pendingApproval: StateFlow<WearPendingApproval?> = _pendingApproval.asStateFlow()

    private val _commandState = MutableStateFlow<WearCommandState>(WearCommandState.Idle)
    val commandState: StateFlow<WearCommandState> = _commandState.asStateFlow()

    /** planId → decision already sent; the card stays visible but inert until the phone clears it. */
    private val _sentDecisions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val sentDecisions: StateFlow<Map<String, Boolean>> = _sentDecisions.asStateFlow()

    fun start() {
        dataClient.addListener(this)
        messageClient.addListener(this)
        try {
            capabilityClient.addListener(this, WearProtocol.CAPABILITY_AGENT)
        } catch (e: Exception) {
            Log.w(TAG, "Capability listener registration failed", e)
        }
        refreshConnection()
    }

    fun stop() {
        dataClient.removeListener(this)
        messageClient.removeListener(this)
        try {
            capabilityClient.removeListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Capability listener removal failed", e)
        }
    }

    /** Re-check for a capable, reachable phone node. */
    fun refreshConnection() {
        scope.launch {
            try {
                val capability = withContext(Dispatchers.IO) {
                    Tasks.await(
                        capabilityClient.getCapability(
                            WearProtocol.CAPABILITY_AGENT,
                            CapabilityClient.FILTER_REACHABLE
                        )
                    )
                }
                val node = capability.nodes.firstOrNull { it.isNearby } ?: capability.nodes.firstOrNull()
                _phoneNodeId.value = node?.id
                _phoneReachable.value = node != null
            } catch (e: Exception) {
                Log.w(TAG, "Capability query failed", e)
                _phoneNodeId.value = null
                _phoneReachable.value = false
            }
        }
    }

    override fun onCapabilityChanged(capabilityInfo: com.google.android.gms.wearable.CapabilityInfo) {
        val node = capabilityInfo.nodes.firstOrNull { it.isNearby } ?: capabilityInfo.nodes.firstOrNull()
        _phoneNodeId.value = node?.id
        _phoneReachable.value = node != null
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                val path = event.dataItem.uri.path ?: continue
                if (event.type == DataEvent.TYPE_DELETED) {
                    when (path) {
                        WearProtocol.PATH_PENDING_APPROVAL -> {
                            _pendingApproval.value = null
                            _sentDecisions.value = emptyMap()
                        }
                        WearProtocol.PATH_STATUS -> _status.value = null
                    }
                    continue
                }
                val payload = try {
                    DataMapItem.fromDataItem(event.dataItem).dataMap
                        .getString(WearProtocol.DATA_KEY_PAYLOAD)
                } catch (e: Exception) {
                    null
                } ?: continue
                try {
                    when (path) {
                        WearProtocol.PATH_STATUS ->
                            _status.value = json.decodeFromString<WearStatusSnapshot>(payload)
                        WearProtocol.PATH_PENDING_APPROVAL ->
                            _pendingApproval.value = json.decodeFromString<WearPendingApproval>(payload)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unparseable data item at $path", e)
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.PATH_COMMAND_RESULT) return
        val result = try {
            json.decodeFromString<WearCommandResult>(event.data.decodeToString())
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable command result", e)
            return
        }
        val current = _commandState.value
        if (current is WearCommandState.Sending && current.correlationId == result.correlationId) {
            _commandState.value = WearCommandState.Result(result.outcome, result.message)
        } else if (current is WearCommandState.TimedOut) {
            // Late answer after the wait window: show it as what it is.
            _commandState.value = WearCommandState.Result(result.outcome, result.message)
        }
    }

    /** Send a dictated/typed command to the phone's agent. */
    fun sendCommand(text: String) {
        val nodeId = _phoneNodeId.value
        if (nodeId == null) {
            _commandState.value = WearCommandState.Result(
                WearCommandOutcome.FAILED,
                "Phone not reachable — nothing was sent."
            )
            return
        }
        val correlationId = UUID.randomUUID().toString()
        val request = WearCommandRequest(
            correlationId = correlationId,
            text = text,
            sentAtMs = System.currentTimeMillis()
        )
        _commandState.value = WearCommandState.Sending(correlationId, text)
        scope.launch {
            val delivered = try {
                withContext(Dispatchers.IO) {
                    Tasks.await(
                        messageClient.sendMessage(
                            nodeId,
                            WearProtocol.PATH_COMMAND,
                            json.encodeToString(request).encodeToByteArray()
                        )
                    )
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Command send failed", e)
                false
            }
            val state = _commandState.value
            if (!delivered) {
                if (state is WearCommandState.Sending && state.correlationId == correlationId) {
                    _commandState.value = WearCommandState.Result(
                        WearCommandOutcome.FAILED,
                        "Could not reach the phone — nothing was sent."
                    )
                }
                refreshConnection()
                return@launch
            }
            // Fire-and-forget transport: waiting is honest, expiring is honest.
            delay(RESULT_WAIT_MS)
            val afterWait = _commandState.value
            if (afterWait is WearCommandState.Sending && afterWait.correlationId == correlationId) {
                _commandState.value = WearCommandState.TimedOut(text)
            }
        }
    }

    /** Send an approve/deny decision for the pending plan card. */
    fun sendApprovalDecision(planId: String, approved: Boolean) {
        val nodeId = _phoneNodeId.value ?: run {
            refreshConnection()
            return
        }
        if (_sentDecisions.value.containsKey(planId)) return
        _sentDecisions.value = _sentDecisions.value + (planId to approved)
        val decision = WearApprovalDecision(
            planId = planId,
            approved = approved,
            decidedAtMs = System.currentTimeMillis()
        )
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Tasks.await(
                        messageClient.sendMessage(
                            nodeId,
                            WearProtocol.PATH_APPROVAL_DECISION,
                            json.encodeToString(decision).encodeToByteArray()
                        )
                    )
                }
            } catch (e: Exception) {
                // Undelivered: un-mark so the user can retry; the phone never
                // saw a decision, so nothing was approved or denied.
                Log.w(TAG, "Approval decision send failed", e)
                _sentDecisions.value = _sentDecisions.value - planId
                refreshConnection()
            }
        }
    }

    fun dismissCommandState() {
        _commandState.value = WearCommandState.Idle
    }

    /** The watch has no voice/text input handler at all — say so in the UI. */
    fun reportInputUnavailable() {
        _commandState.value = WearCommandState.Result(
            WearCommandOutcome.FAILED,
            "Voice input isn't available on this watch."
        )
    }

    companion object {
        private const val TAG = "WearRepository"
        private const val RESULT_WAIT_MS = 90_000L
    }
}
