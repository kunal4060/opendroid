package com.opendroid.ai.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.voice.SpeechRecognitionEngine
import com.opendroid.ai.core.voice.TextToSpeechEngine
import com.opendroid.ai.core.voice.VoiceApprovalIntent
import com.opendroid.ai.core.voice.VoiceApprovalParser
import com.opendroid.ai.core.voice.VoiceConversationController
import com.opendroid.ai.core.voice.VoiceSessionEffect
import com.opendroid.ai.core.voice.VoiceSessionStateStore
import com.opendroid.ai.core.voice.WakeWordDetector
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OpenDroidService : Service() {

    @Inject
    lateinit var agentLoop: AgentLoop

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var mcpServer: McpServer

    // App-scoped singleton (AppModule) shared with chat read-aloud — this
    // service no longer owns the engine's lifecycle.
    @Inject
    lateinit var textToSpeechEngine: TextToSpeechEngine

    // D2: UI mirror of the continuous voice session (chat renders its bar from this).
    @Inject
    lateinit var voiceSessionStateStore: VoiceSessionStateStore

    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var speechRecognitionEngine: SpeechRecognitionEngine

    // D2 continuous voice conversation. The controller is pure decision
    // logic; this service owns the engines and performs its effects.
    private val voiceController = VoiceConversationController()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionCapRunnable: Runnable? = null
    private var listenWatchdogRunnable: Runnable? = null
    private var listenCycleId = 0
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            if (voiceController.isActive) {
                applyVoiceEffects(voiceController.onAudioFocusLost())
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var showFloatingButton = false
    @Volatile private var pendingApprovalListen = false

    companion object {
        const val ACTION_TRIGGER_RECORD = "com.opendroid.ai.action.TRIGGER_RECORD"
        const val ACTION_START_VOICE_SESSION = "com.opendroid.ai.action.START_VOICE_SESSION"
        const val ACTION_STOP_VOICE_SESSION = "com.opendroid.ai.action.STOP_VOICE_SESSION"
        const val ACTION_INTERRUPT_VOICE_SESSION = "com.opendroid.ai.action.INTERRUPT_VOICE_SESSION"

        // D2 session policy: hard cap so a forgotten session never holds
        // the mic forever, watchdog so a hung recognizer can't either, and
        // a short settle delay so the opening greeting isn't transcribed
        // as the user's first utterance.
        private const val SESSION_CAP_MS = 10 * 60 * 1000L
        private const val LISTEN_WATCHDOG_MS = 20 * 1000L
        private const val GREETING_SETTLE_MS = 900L

        private const val CHANNEL_ID = "opendroid_channel"
        private const val NOTIFICATION_ID = 2024

        fun start(context: Context) {
            val intent = Intent(context, OpenDroidService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OpenDroidService::class.java)
            context.stopService(intent)
        }

        /** D2: ask the service to open a continuous voice session (starts the service if needed). */
        fun startVoiceSession(context: Context) {
            val intent = Intent(context, OpenDroidService::class.java).apply {
                action = ACTION_START_VOICE_SESSION
            }
            context.startForegroundService(intent)
        }

        /** D2: end the running voice session, if any. */
        fun stopVoiceSession(context: Context) {
            val intent = Intent(context, OpenDroidService::class.java).apply {
                action = ACTION_STOP_VOICE_SESSION
            }
            context.startService(intent)
        }

        /** D2: barge-in from the UI — stop current speech and listen. */
        fun interruptVoiceSession(context: Context) {
            val intent = Intent(context, OpenDroidService::class.java).apply {
                action = ACTION_INTERRUPT_VOICE_SESSION
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // Initialize engines (the TTS engine is injected — see AppModule)
        wakeWordDetector = WakeWordDetector(this)
        speechRecognitionEngine = SpeechRecognitionEngine(this)

        // Bind Agent Loop TTS
        agentLoop.onSpeakCallback = { text ->
            textToSpeechEngine.speak(text)
        }

        // Set TTS completion listener to transition back to Idle
        textToSpeechEngine.onCompletionListener = {
            // Only Speaking resets to Idle - speech that happens to play while a
            // plan sits in PlanProposed must not knock the approval gate over.
            if (agentLoop.agentState.value is AgentState.Speaking) {
                agentLoop.setAgentState(AgentState.Idle)
            }
            if (pendingApprovalListen) {
                pendingApprovalListen = false
                startListeningForApproval()
            } else if (voiceController.state == VoiceConversationController.State.SPEAKING) {
                // D2: session speech (agent reply or the still-listening
                // prompt) finished — advance the conversation loop.
                disarmBargeInWatcher()
                applyVoiceEffects(voiceController.onSpeechFinished())
            }
        }

        // Start Foreground Notification
        createNotificationChannel()
        startForegroundCompat()
        mcpServer.start()

        // Monitor floating button config to start/stop wake word detection dynamically
        serviceScope.launch {
            settingsRepository.llmConfig.collectLatest { config ->
                showFloatingButton = config.showFloatingButton
                // D1: forward the wake-word engine/keyword/sensitivity; the
                // detector restarts its engine only when these actually change.
                wakeWordDetector.applySettings(
                    engineId = config.wakeWordEngine,
                    keywordPhrase = config.wakeWordKeyword,
                    sensitivity = config.wakeWordSensitivity
                )
                if (showFloatingButton) {
                    wakeWordDetector.stopListening()
                } else if (!voiceController.isActive) {
                    // D2: while a voice session runs it owns the wake
                    // detector (barge-in watcher); never re-arm idle wake
                    // underneath it on a config emission.
                    startWakeWordDetection()
                }
            }
        }

        // Hands-free plan approval (upstream issue 18 spec, voice section):
        // speak the prompt once per proposed plan; auto-listen for the reply
        // in wake-word mode, where the user is known to be voice-driven —
        // and in a D2 voice session regardless of the floating-button mode,
        // since the session is voice-driven by definition.
        serviceScope.launch {
            var promptedPlanId: String? = null
            agentLoop.agentState.collectLatest { state ->
                if (state is AgentState.PlanProposed && state.plan.planId != promptedPlanId) {
                    promptedPlanId = state.plan.planId
                    if (!showFloatingButton || voiceController.isActive) {
                        pendingApprovalListen = true
                    }
                    textToSpeechEngine.speak(
                        "I've planned: ${state.plan.goal}, ${state.plan.estimatedSteps} steps. " +
                        "Say approve to run, or cancel."
                    )
                }
            }
        }

        // D2 session choreography: translate agent-loop state changes into
        // controller events while a voice session is active. AgentState
        // .Speaking is only ever set for turn-final speech (the final reply
        // / plan summary), so observing it is a reliable "reply is being
        // spoken" signal; intermediate plan speeches don't set it.
        serviceScope.launch {
            agentLoop.agentState.collectLatest { state ->
                if (!voiceController.isActive) return@collectLatest
                when (state) {
                    is AgentState.Speaking -> {
                        applyVoiceEffects(voiceController.onSpeechStarted())
                        if (voiceController.state == VoiceConversationController.State.SPEAKING) {
                            armBargeInWatcher()
                        }
                    }
                    is AgentState.PlanProposed -> voiceController.onPlanProposed()
                    is AgentState.Error -> {
                        if (voiceController.state == VoiceConversationController.State.THINKING) {
                            applyVoiceEffects(voiceController.onTurnFailed())
                        }
                    }
                    is AgentState.Idle -> {
                        // Turn settled with no turn-final speech (rejected
                        // plan, a question to answer by voice, ...): the
                        // next utterance simply becomes the next turn.
                        if (voiceController.state == VoiceConversationController.State.THINKING) {
                            applyVoiceEffects(voiceController.onAgentTurnSettled())
                        }
                    }
                    else -> Unit
                }
                syncVoiceSessionUi()
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = createNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Starting a microphone-type FGS without RECORD_AUDIO granted throws a
            // SecurityException on Android 14+, and starting one from BOOT_COMPLETED is
            // prohibited on Android 15 even with the permission. Fall back to specialUse
            // until the microphone is actually usable.
            val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val type = if (micGranted) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            try {
                androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            } catch (e: SecurityException) {
                androidx.core.app.ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // FOREGROUND_SERVICE_TYPE_MICROPHONE is an API 30 constant; API 29 devices
            // fall through to plain startForeground, which uses the manifest-declared types.
            androidx.core.app.ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startWakeWordDetection() {
        wakeWordDetector.startListening {
            // Wake word opens a D2 continuous voice session: the user keeps
            // talking turn after turn until they end it or go silent.
            startVoiceSession()
        }
    }

    private fun startListeningForQuery() {
        // Temporarily pause wake word to avoid hearing itself
        wakeWordDetector.stopListening()

        // Set agent state to Listening
        agentLoop.setAgentState(AgentState.Listening)

        // Start speech recognizer for query input
        speechRecognitionEngine.startListening(
            onResult = { query ->
                agentLoop.processQuery(query, this)
                // Resume wake word detection only if floating button is disabled
                if (!showFloatingButton) {
                    startWakeWordDetection()
                } else {
                    agentLoop.setAgentState(AgentState.Idle)
                }
            },
            onError = { _ ->
                agentLoop.setAgentState(AgentState.Idle)
                // Resume wake word detection only if floating button is disabled
                if (!showFloatingButton) {
                    startWakeWordDetection()
                }
            }
        )
    }

    /**
     * One-shot reply capture after the spoken approval prompt. Unrecognized
     * speech (or an error) is a deliberate no-op: the plan stays in
     * PlanProposed with the visual modal still on screen. Never a grant path -
     * nothing spoken may widen the allowlist.
     *
     * Inside a D2 voice session the same capture runs, but the outcome is
     * routed through the session controller: a decision returns the loop to
     * the agent turn, while no-decision ends the voice session (the plan
     * itself stays proposed in chat for a touch decision — silence is not
     * consent, and it is not a rejection either).
     */
    private fun startListeningForApproval() {
        wakeWordDetector.stopListening()
        speechRecognitionEngine.startListening(
            onResult = { utterance ->
                if (voiceController.isActive) {
                    when (VoiceApprovalParser.parse(utterance)) {
                        VoiceApprovalIntent.APPROVE -> {
                            voiceController.onApprovalResolved()
                            agentLoop.approveProposedPlan(this)
                        }
                        VoiceApprovalIntent.REJECT -> {
                            voiceController.onApprovalResolved()
                            agentLoop.rejectProposedPlan()
                        }
                        VoiceApprovalIntent.NONE ->
                            applyVoiceEffects(voiceController.onApprovalUnresolved())
                    }
                    syncVoiceSessionUi()
                } else {
                    when (VoiceApprovalParser.parse(utterance)) {
                        VoiceApprovalIntent.APPROVE -> agentLoop.approveProposedPlan(this)
                        VoiceApprovalIntent.REJECT -> agentLoop.rejectProposedPlan()
                        VoiceApprovalIntent.NONE -> Unit
                    }
                    if (!showFloatingButton) startWakeWordDetection()
                }
            },
            onError = { _ ->
                if (voiceController.isActive) {
                    applyVoiceEffects(voiceController.onApprovalUnresolved())
                } else if (!showFloatingButton) {
                    startWakeWordDetection()
                }
            }
        )
    }

    // ---------------------------------------------------------------------
    // D2 — continuous voice conversation session driver.
    //
    // Mic ownership rule (one owner at a time): STT recognizer while
    // LISTENING, the wake-phrase watcher while SPEAKING (barge-in), nobody
    // while THINKING/APPROVING beyond the existing approval capture.
    // ---------------------------------------------------------------------

    private fun startVoiceSession() {
        if (voiceController.isActive) return
        wakeWordDetector.stopListening()
        if (!requestVoiceAudioFocus()) {
            // Another app owns the audio right now — stay in idle wake mode
            // rather than starting a session that cannot hear or speak.
            if (!showFloatingButton) startWakeWordDetection()
            return
        }
        textToSpeechEngine.speak("OpenDroid online.")
        armSessionCap()
        // Small settle delay so the greeting isn't transcribed as the
        // user's first utterance. Guarded: a stop inside the window must
        // not resurrect the mic.
        val effects = voiceController.start()
        syncVoiceSessionUi()
        mainHandler.postDelayed({
            if (voiceController.isActive) applyVoiceEffects(effects)
        }, GREETING_SETTLE_MS)
    }

    private fun listenForSessionTurn() {
        // Temporarily pause wake word to avoid hearing itself
        wakeWordDetector.stopListening()
        agentLoop.setAgentState(AgentState.Listening)
        speechRecognitionEngine.startListening(
            onResult = { text ->
                disarmListenWatchdog()
                applyVoiceEffects(voiceController.onUtterance(text))
            },
            onError = { error ->
                disarmListenWatchdog()
                val fatal = error.contains("not available", ignoreCase = true) ||
                    error.contains("Insufficient permissions", ignoreCase = true)
                applyVoiceEffects(voiceController.onListenFailure(fatal))
            }
        )
        armListenWatchdog()
    }

    private fun applyVoiceEffects(effects: List<VoiceSessionEffect>) {
        for (effect in effects) {
            when (effect) {
                VoiceSessionEffect.BeginListening -> listenForSessionTurn()
                VoiceSessionEffect.CancelListening -> {
                    disarmListenWatchdog()
                    speechRecognitionEngine.cancel()
                }
                is VoiceSessionEffect.SubmitQuery ->
                    // Same call shape as the one-shot voice path: no explicit
                    // session id, so the turn lands in the current chat
                    // session and follow-ups share context with typed chat.
                    agentLoop.processQuery(effect.text, this)
                is VoiceSessionEffect.SpeakText -> textToSpeechEngine.speak(effect.text)
                VoiceSessionEffect.StopSpeaking -> {
                    disarmBargeInWatcher()
                    textToSpeechEngine.stop()
                }
                is VoiceSessionEffect.EndSession -> endVoiceSession(effect.farewell)
            }
        }
        syncVoiceSessionUi()
    }

    private fun endVoiceSession(farewell: String?) {
        disarmSessionCap()
        disarmListenWatchdog()
        disarmBargeInWatcher()
        speechRecognitionEngine.cancel()
        if (agentLoop.agentState.value is AgentState.Listening) {
            agentLoop.setAgentState(AgentState.Idle)
        }
        abandonVoiceAudioFocus()
        if (!showFloatingButton) {
            startWakeWordDetection()
        }
        // Spoken after teardown as an ordinary one-shot line (the same way
        // the wake greeting is spoken) — it does not re-open the session.
        if (farewell != null) {
            textToSpeechEngine.speak(farewell)
        }
    }

    /**
     * Barge-in watcher: while the assistant speaks, the wake detector owns
     * the mic with its usual wake phrase. Hearing it stops the speech and
     * re-opens listening. Using the wake phrase (not a bare "stop") keeps
     * the assistant's own spoken words from triggering it in all but the
     * rarest cases; the trade-off is documented in the D2 report.
     */
    private fun armBargeInWatcher() {
        wakeWordDetector.startListening {
            applyVoiceEffects(voiceController.onBargeIn())
        }
    }

    private fun disarmBargeInWatcher() {
        wakeWordDetector.stopListening()
    }

    private fun armSessionCap() {
        disarmSessionCap()
        val runnable = Runnable {
            if (voiceController.isActive) {
                applyVoiceEffects(voiceController.onSessionCapReached())
            }
        }
        sessionCapRunnable = runnable
        mainHandler.postDelayed(runnable, SESSION_CAP_MS)
    }

    private fun disarmSessionCap() {
        sessionCapRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionCapRunnable = null
    }

    private fun armListenWatchdog() {
        disarmListenWatchdog()
        val cycle = ++listenCycleId
        val runnable = Runnable {
            if (voiceController.isActive &&
                voiceController.state == VoiceConversationController.State.LISTENING &&
                cycle == listenCycleId
            ) {
                speechRecognitionEngine.cancel()
                applyVoiceEffects(voiceController.onListenTimeout())
            }
        }
        listenWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, LISTEN_WATCHDOG_MS)
    }

    private fun disarmListenWatchdog() {
        listenWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        listenWatchdogRunnable = null
    }

    private fun requestVoiceAudioFocus(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
            .build()
        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonVoiceAudioFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.abandonAudioFocusRequest(request)
    }

    private fun syncVoiceSessionUi() {
        voiceSessionStateStore.update(
            active = voiceController.isActive,
            phase = voiceController.phase,
            turnsCompleted = voiceController.turnsCompleted
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_RECORD -> {
                // The session owns the mic while active; the one-shot
                // accessibility-gesture listen must not fight it.
                if (!voiceController.isActive) {
                    startListeningForQuery()
                }
            }
            ACTION_START_VOICE_SESSION -> startVoiceSession()
            ACTION_STOP_VOICE_SESSION -> {
                if (voiceController.isActive) {
                    applyVoiceEffects(voiceController.onStopRequested())
                }
            }
            ACTION_INTERRUPT_VOICE_SESSION -> {
                if (voiceController.isActive) {
                    applyVoiceEffects(voiceController.onBargeIn())
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        // D2: release session resources (timers, audio focus, UI mirror).
        disarmSessionCap()
        disarmListenWatchdog()
        abandonVoiceAudioFocus()
        voiceSessionStateStore.clear()
        serviceScope.cancel()
        mcpServer.stop()
        wakeWordDetector.destroy()
        speechRecognitionEngine.destroy()
        // textToSpeechEngine is an app-scoped singleton shared with the chat
        // UI (read-aloud) — deliberately not destroyed with this service.
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenDroid Agent Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps OpenDroid background agent alive"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenDroid Active")
            .setContentText("Listening for wake word 'OpenDroid'")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
