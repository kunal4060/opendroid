package com.opendroid.ai.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.llm.AttachmentException
import com.opendroid.ai.core.llm.AttachmentProcessor
import com.opendroid.ai.core.llm.PendingAttachment
import com.opendroid.ai.core.llm.VisionSupport
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.llm.ProviderSetupReadiness
import com.opendroid.ai.core.llm.RoutingStatus
import com.opendroid.ai.core.llm.RoutingStatusRepository
import com.opendroid.ai.core.agent.ChatErrorPrimaryAction
import com.opendroid.ai.core.agent.ChatErrorUiState
import com.opendroid.ai.core.agent.primaryAction
import com.opendroid.ai.core.intent.ExternalIntentRouter
import com.opendroid.ai.core.intent.ExternalLaunch
import com.opendroid.ai.core.llm.ModelFetchOutcome
import com.opendroid.ai.core.llm.ModelFetcher
import com.opendroid.ai.core.llm.OnDeviceBackend
import com.opendroid.ai.core.llm.OnDeviceModelRegistry
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.rag.KnowledgeBaseRepository
import com.opendroid.ai.core.share.SharedContent
import com.opendroid.ai.core.share.SharedImageRef
import com.opendroid.ai.core.share.SharedImageStore
import com.opendroid.ai.data.db.entities.ModelEntity
import com.opendroid.ai.data.db.entities.ModelStatus
import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Persona
import com.opendroid.ai.data.models.PersonaKind
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.effectiveWithSessionOverride
import com.opendroid.ai.data.models.isProviderConfiguredForUse
import com.opendroid.ai.data.models.resolvedAutoMode
import com.opendroid.ai.data.models.selectedModelFor
import com.opendroid.ai.data.models.withActiveProvider
import com.opendroid.ai.data.models.withSelectedModel
import com.opendroid.ai.data.repository.ChatSession
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.ModelRepository
import com.opendroid.ai.data.repository.PersonaRepository
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.ui.components.EffectiveProviderModel
import com.opendroid.ai.ui.components.ModelPickerUiState
import com.opendroid.ai.ui.components.PickerModelOption
import com.opendroid.ai.ui.components.PickerProviderOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoop: AgentLoop,
    private val conversationRepository: ConversationRepository,
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val modelFetcher: dagger.Lazy<ModelFetcher>,
    private val routingStatusRepository: RoutingStatusRepository,
    private val providerSetupReadiness: ProviderSetupReadiness,
    private val textToSpeechEngine: com.opendroid.ai.core.voice.TextToSpeechEngine,
    private val attachmentProcessor: AttachmentProcessor,
    private val externalIntentRouter: ExternalIntentRouter,
    private val sharedImageStore: SharedImageStore,
    private val habitRoutineEngine: com.opendroid.ai.core.routine.HabitRoutineEngine,
    private val pendingAssistantCommand: com.opendroid.ai.core.assistant.PendingAssistantCommand,
    private val knowledgeBaseRepository: KnowledgeBaseRepository
) : ViewModel() {

    val llmConfig: StateFlow<LLMConfig> = settingsRepository.llmConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LLMConfig())

    /**
     * The A10 limited state: true while no usable AI provider is configured, so
     * Chat shows its persistent setup banner instead of letting sends fail
     * against a provider that was never set up. Driven by the same readiness
     * state onboarding uses, so the banner clears the moment any setup path —
     * onboarding, Settings, or a finished on-device download — lands.
     */
    val providerSetupNeeded: StateFlow<Boolean> = providerSetupReadiness.canAnswer
        .map { canAnswer -> !canAnswer }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── Inbound shares (C2) ────────────────────────────────────────────
    // MainActivity publishes share intents to the router; ChatScreen
    // collects this flow — and ChatScreen only composes once the A5 lock
    // gate and the splash/onboarding routing have resolved, so shared
    // content can never be staged behind the lock or ahead of onboarding.

    /** External launches to consume (shares today; C1/C3/C7 ride the same router). */
    val externalLaunches: SharedFlow<ExternalLaunch> = externalIntentRouter.launches

    /** Claims [launch] for handling; false when another consumer already took it. */
    fun claimExternalLaunch(launch: ExternalLaunch): Boolean =
        externalIntentRouter.claim(launch)

    /** Draft staged by an accepted share; ChatScreen moves it into the composer once. */
    private val _pendingComposerDraft = MutableStateFlow<String?>(null)
    val pendingComposerDraft: StateFlow<String?> = _pendingComposerDraft

    /** Shared images waiting to ride the user's next sent message in this chat. */
    private val _pendingSharedImages = MutableStateFlow<List<SharedImageRef>>(emptyList())
    val pendingSharedImages: StateFlow<List<SharedImageRef>> = _pendingSharedImages

    /** One-line notice about a share (e.g. a share that carried no content at all). */
    private val _shareNotice = MutableStateFlow<String?>(null)
    val shareNotice: StateFlow<String?> = _shareNotice

    /**
     * Stages a share into a brand-new chat: image streams are copied into
     * app-private storage first (the sender's grant is temporary), then the
     * chat is created and the share's text becomes the composer draft with
     * the images pending as attachments. Nothing is sent — the user still
     * writes (or edits) the instruction and hits send themselves, and the
     * usual approval modes apply to whatever the agent then does.
     */
    fun acceptSharedContent(launch: ExternalLaunch.SharedContent) {
        val parsed = launch.parsed
        viewModelScope.launch {
            val images = withContext(Dispatchers.IO) {
                parsed.streamUris.mapNotNull { uri ->
                    sharedImageStore.importImage(uri, parsed.declaredMimeType)
                }
            }
            val content = SharedContent.fromRaw(parsed.text, parsed.subject, images)
            if (content.isEmpty) {
                _shareNotice.value = "That share didn't include any text or images."
                return@launch
            }
            conversationRepository.createSession(content.suggestedSessionTitle())
            // A second share arriving before the first is sent keeps the
            // earlier images pending too rather than silently dropping them.
            _pendingSharedImages.value = _pendingSharedImages.value + images
            _pendingComposerDraft.value = content.composerPrefill()
        }
    }

    /** Marks the staged draft as moved into the composer. */
    fun consumeComposerDraft() {
        _pendingComposerDraft.value = null
    }

    /** Removes a pending shared image (chip dismissed) and deletes its private copy. */
    fun removePendingSharedImage(image: SharedImageRef) {
        _pendingSharedImages.value = _pendingSharedImages.value - image
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sharedImageStore.delete(image) }
        }
    }

    fun dismissShareNotice() {
        _shareNotice.value = null
    }
    /**
     * C3: a command parked by an assistant invocation (ACTION_ASSIST),
     * waiting for ChatScreen to deliver it through [sendMessage] — the
     * same path as typed input, including the offline-no-route guard. The
     * screen only exists once the app-lock gate has opened, so a parked
     * command can never bypass the lock.
     */
    val pendingAssistantQuery: StateFlow<String?> = pendingAssistantCommand.pendingQuery

    /** Clears the parked assistant command; returns it so the caller can send it exactly once. */
    fun consumePendingAssistantQuery(): String? = pendingAssistantCommand.consume()

    // Scoped to whichever session is current; re-collects automatically when the
    // user switches chats since the repository's flow is driven off the same
    // reactive "current session" pointer that switchToSession() flips.
    val conversationHistory: StateFlow<List<ChatMessage>> = conversationRepository.conversations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Every chat session, most recently active first. */
    val sessions: StateFlow<List<ChatSession>> = conversationRepository.sessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Id of whichever session is current; null only before the very first session exists. */
    val currentSessionId: StateFlow<String?> = conversationRepository.currentSessionId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // ── Per-chat model picker (A2) ─────────────────────────────────────
    // The current chat's persisted override columns, resolved against the
    // live global config with the exact resolver the send path uses - the
    // chip and the request can never disagree about which provider/model
    // will answer, and an override whose provider lost its key in Settings
    // falls back to (and displays as) the global default.

    /**
     * Provider + model the CURRENT chat will actually be answered by, and
     * whether that comes from a per-chat override. Drives the header chip.
     */
    val effectiveProviderModel: StateFlow<EffectiveProviderModel> = combine(
        llmConfig, sessions
    ) { config, sessionList ->
        val current = sessionList.firstOrNull { it.isCurrent }
        val effective = current?.let {
            config.effectiveWithSessionOverride(it.providerOverride, it.modelOverride)
        }
        val base = effective ?: config
        val provider = ProviderCatalog.canonicalName(base.activeProvider)
        EffectiveProviderModel(
            provider = provider,
            model = base.selectedModelFor(provider),
            isOverride = effective != null
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EffectiveProviderModel(
            provider = ProviderCatalog.canonicalName(LLMConfig().activeProvider),
            model = LLMConfig().activeModel,
            isOverride = false
        )
    )

    /** On-device model rows (download states) for the picker's real availability data. */
    private val downloadedModels: StateFlow<List<ModelEntity>> = modelRepository.allModelsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Canonical provider whose live model list is being fetched for the picker, if any. */
    private val _fetchingProvider = MutableStateFlow<String?>(null)

    /** Everything the picker sheet renders, built from the real registry/config/DB state. */
    val modelPickerState: StateFlow<ModelPickerUiState> = combine(
        llmConfig, downloadedModels, _fetchingProvider
    ) { config, downloaded, fetching ->
        ModelPickerUiState(
            providers = buildPickerProviders(config, downloaded),
            fetchingProvider = fetching
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ModelPickerUiState()
    )

    private fun buildPickerProviders(
        config: LLMConfig,
        downloadedModels: List<ModelEntity>
    ): List<PickerProviderOption> {
        val downloadedStates = setOf(ModelStatus.DOWNLOADED, ModelStatus.READY, ModelStatus.LOADING)
        val entitiesById = downloadedModels.associateBy { it.id }
        val seen = mutableSetOf<String>()
        return ProviderCatalog.providers.mapNotNull { spec ->
            // The catalog holds one legacy alias entry whose canonical name
            // duplicates the on-device provider - list each provider once.
            val canonical = spec.canonicalName
            if (!seen.add(canonical)) return@mapNotNull null
            val onDevice = ProviderCatalog.isOnDevice(canonical)
            val models = if (onDevice) {
                buildOnDevicePickerModels(canonical, entitiesById, downloadedModels, downloadedStates)
            } else {
                val cached = config.modelCache[canonical].orEmpty()
                // Cloud models carry no download state: `available` is false
                // only for on-device models that are not downloaded (see
                // PickerModelOption); provider-level gating lives in
                // isConfigured above and in the send-time resolver.
                val options = if (cached.isNotEmpty()) {
                    cached.map {
                        PickerModelOption(id = it.id, displayName = it.displayName, available = true)
                    }
                } else {
                    val seed = ProviderCatalog.defaultModel(canonical)
                    listOf(PickerModelOption(id = seed, displayName = seed, available = true))
                }
                // The model this provider would actually answer with must
                // always be listed, even before/without a live fetch.
                val selected = config.selectedModelFor(canonical)
                if (options.any { it.id == selected }) {
                    options
                } else {
                    listOf(
                        PickerModelOption(id = selected, displayName = selected, available = true)
                    ) + options
                }
            }
            PickerProviderOption(
                provider = canonical,
                displayName = spec.displayName,
                isOnDevice = onDevice,
                requiresApiKey = ProviderCatalog.requiresApiKey(canonical),
                isConfigured = isProviderConfiguredForUse(config, canonical),
                latencyMs = config.latencyBenchmarks[canonical]
                    ?: config.latencyBenchmarks[spec.displayName],
                models = models
            )
        }
    }

    private fun buildOnDevicePickerModels(
        canonicalProvider: String,
        entitiesById: Map<String, ModelEntity>,
        downloadedModels: List<ModelEntity>,
        downloadedStates: Set<ModelStatus>
    ): List<PickerModelOption> {
        val specs = when (canonicalProvider) {
            "LiteRT-LM (On-device)" -> OnDeviceModelRegistry.liteRTOnly
            else -> OnDeviceModelRegistry.allModels
        }
        val specOptions = specs.map { spec ->
            when (spec.backend) {
                // AI Core models are system-provided (Gemini Nano via ML Kit);
                // there is nothing to download, device support is a runtime
                // property of the provider itself.
                OnDeviceBackend.AI_CORE -> PickerModelOption(
                    id = spec.id,
                    displayName = spec.displayName,
                    available = true,
                    availabilityLabel = "Built-in"
                )
                OnDeviceBackend.LITERT_LM -> {
                    val downloaded = entitiesById[spec.id]?.status in downloadedStates
                    PickerModelOption(
                        id = spec.id,
                        displayName = spec.displayName,
                        available = downloaded,
                        availabilityLabel = if (downloaded) null else "Not downloaded"
                    )
                }
            }
        }
        // User-imported models live only in the models table; surface the
        // downloaded ones alongside the catalog specs.
        val customOptions = downloadedModels
            .filter { OnDeviceModelRegistry.isCustomId(it.id) && it.status in downloadedStates }
            .map { PickerModelOption(id = it.id, displayName = it.name, available = true) }
        val options = specOptions + customOptions
        // A persisted on-device selection that is neither a registry spec nor
        // an imported row (e.g. a legacy provider default) still gets listed -
        // it is what the provider would be asked for.
        val selected = llmConfig.value.selectedModelFor(canonicalProvider)
        return if (options.any { it.id == selected }) {
            options
        } else {
            // Neither a registry spec nor a downloaded import: the persisted
            // selection's real state is whatever the models table holds for
            // it (no row = not on this device), rendered exactly like any
            // other not-downloaded on-device model.
            val selectedDownloaded = entitiesById[selected]?.status in downloadedStates
            listOf(
                PickerModelOption(
                    id = selected,
                    displayName = selected,
                    available = selectedDownloaded,
                    availabilityLabel = if (selectedDownloaded) null else "Not downloaded"
                )
            ) + options
        }
    }

    /**
     * Applies a per-chat model pick to the CURRENT session only. The global
     * config is not touched; the pick persists on the session row and is
     * resolved into a config snapshot at send time.
     */
    fun setSessionModelOverride(provider: String, model: String) {
        viewModelScope.launch {
            val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
                ?: conversationRepository.ensureCurrentSessionId()
            conversationRepository.updateSessionModelOverride(
                sessionId,
                ProviderCatalog.canonicalName(provider),
                model
            )
        }
    }

    /** Removes the current chat's override so it follows the account default again. */
    fun clearSessionModelOverride() {
        viewModelScope.launch {
            val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
                ?: conversationRepository.ensureCurrentSessionId()
            conversationRepository.updateSessionModelOverride(sessionId, null, null)
        }
    }

    // ── Per-chat persona (B6) ──────────────────────────────────────────
    // The binding lives in PersonaRepository (sessionId → personaId), so it
    // persists across restarts, is strictly per chat, and disappears with
    // the persona if the persona is deleted. AgentLoop resolves the same
    // binding at send time and prepends the persona's prompt to the system
    // prompt — the chip below and the request path read one source.

    /** The whole persona library (personas + quick commands) for the picker sheet. */
    val personas: StateFlow<List<Persona>> = personaRepository.personas
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The persona bound to the CURRENT chat, or null when the chat follows
     * the default prompt (no binding, or the bound persona was deleted).
     * Drives the header persona chip.
     */
    val activePersona: StateFlow<Persona?> = combine(
        sessions, personaRepository.personas, personaRepository.sessionBindings
    ) { sessionList, personaList, bindings ->
        val current = sessionList.firstOrNull { it.isCurrent }
        val boundId = current?.let { bindings[it.id] }
        personaList.firstOrNull { it.id == boundId && it.kind == PersonaKind.PERSONA }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Binds a persona to the CURRENT chat only; null clears the binding. */
    fun setSessionPersona(personaId: String?) {
        viewModelScope.launch {
            val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
                ?: conversationRepository.ensureCurrentSessionId()
            personaRepository.setSessionPersona(sessionId, personaId)
        }
    }

    /**
     * Promotes a pick to the account-wide default via the same config
     * helpers Settings uses, then clears the current chat's override - the
     * chat now follows the default, which is exactly what was just picked.
     */
    fun setGlobalDefaultModel(provider: String, model: String) {
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.withActiveProvider(provider).withSelectedModel(provider, model)
            }
            val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
                ?: conversationRepository.ensureCurrentSessionId()
            conversationRepository.updateSessionModelOverride(sessionId, null, null)
        }
    }

    /**
     * Refreshes a cloud provider's live model list into the shared model
     * cache (the same ModelFetcher + persistence Settings uses) when the
     * picker expands it and the cache is empty or stale. Failures keep the
     * catalog-seed fallback the sheet already shows - it stays usable and
     * the next expansion retries, since no fetch timestamp is stamped.
     */
    fun ensureModelsFetched(provider: String) {
        val canonical = ProviderCatalog.canonicalName(provider)
        if (ProviderCatalog.isOnDevice(canonical)) return
        val config = llmConfig.value
        if (!isProviderConfiguredForUse(config, canonical)) return
        val cache = config.modelCache[canonical].orEmpty()
        val lastFetch = config.lastModelFetch[canonical] ?: 0L
        if (cache.isNotEmpty() && System.currentTimeMillis() - lastFetch <= MODEL_CACHE_TTL_MS) return
        if (_fetchingProvider.value == canonical) return
        _fetchingProvider.value = canonical
        viewModelScope.launch {
            try {
                when (val outcome = modelFetcher.get().fetchModels(canonical)) {
                    is ModelFetchOutcome.Success ->
                        settingsRepository.saveModelCache(canonical, outcome.models)
                    else -> Unit // NeedsCredentials/Failed: seed fallback stays, retry next time.
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("ChatViewModel", "Model fetch for $canonical failed: ${e.message}")
            } finally {
                if (_fetchingProvider.value == canonical) _fetchingProvider.value = null
            }
        }
    }

    /**
     * The config snapshot a task for [session] must be answered with: the
     * global config with the session's override applied, or null when the
     * session has no usable override (the global path then runs unchanged).
     */
    private fun configOverrideFor(session: ChatSession?): LLMConfig? =
        session?.let {
            llmConfig.value.effectiveWithSessionOverride(it.providerOverride, it.modelOverride)
        }

    val agentState: StateFlow<AgentState> = agentLoop.agentState

    /**
     * Live chat routing state (A8): which provider/on-device backend will
     * answer, or that no route exists while offline. Rendered as the header
     * badge and consulted by the send paths below — one shared state, so
     * what the badge says and what sending does can never disagree.
     */
    val routingStatus: StateFlow<RoutingStatus> = routingStatusRepository.status

    /**
     * [AgentLoop.chatError], but scoped to whichever chat is on screen - the same rule
     * [visibleAgentState] applies to the shared agent state. An error raised by a task
     * in chat A must never render its recovery card inside chat B; the underlying error
     * stays published so switching back to its own chat shows it again.
     */
    val chatError: StateFlow<ChatErrorUiState?> = combine(
        agentLoop.chatError, sessions
    ) { error, sessionList ->
        val current = sessionList.firstOrNull { it.isCurrent }?.id
        error?.takeIf { it.sessionId == current }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun dismissChatError() {
        agentLoop.dismissChatError()
    }

    // ── Read-aloud (message action) ─────────────────────────────────────
    // The shared TTS engine flushes on every speak() call and platform TTS
    // truncates very long utterances, so a reply is fed to it one
    // sentence-chunk at a time: each chunk's completion (reported through
    // the engine's registered-listener hook, which leaves the foreground
    // service's own completion listener untouched) advances the queue.

    /** Id of the message currently being read aloud, or null when idle. */
    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId

    private val speechQueue = ArrayDeque<String>()

    // One stable instance so it can be removed again — an anonymous lambda
    // created per call could never be unregistered.
    private val speechCompletionListener: () -> Unit = { onSpeechChunkComplete() }

    private fun onSpeechChunkComplete() {
        if (_speakingMessageId.value == null) return
        val next = speechQueue.removeFirstOrNull()
        if (next != null) {
            textToSpeechEngine.speak(next)
        } else {
            finishSpeaking()
        }
    }

    private fun finishSpeaking() {
        textToSpeechEngine.removeCompletionListener(speechCompletionListener)
        _speakingMessageId.value = null
    }

    /**
     * Reads [message] aloud through the existing TTS pipeline (ElevenLabs
     * when configured, local TTS otherwise), with markdown stripped so the
     * syntax isn't spoken. Tapping read-aloud on the message that is
     * already speaking stops it instead.
     */
    fun speakMessage(message: ChatMessage) {
        if (_speakingMessageId.value == message.id) {
            stopSpeaking()
            return
        }
        stopSpeaking()
        val chunks = com.opendroid.ai.core.voice.SpeechText.chunkForSpeech(
            com.opendroid.ai.core.voice.SpeechText.stripMarkdown(message.text)
        )
        if (chunks.isEmpty()) return
        speechQueue.addAll(chunks.drop(1))
        _speakingMessageId.value = message.id
        textToSpeechEngine.addCompletionListener(speechCompletionListener)
        textToSpeechEngine.speak(chunks.first())
    }

    /** Stops read-aloud and drops any queued chunks. */
    fun stopSpeaking() {
        // Unregister BEFORE stopping: a stopped utterance reports back as an
        // error/completion, which must not advance a queue that is being
        // torn down.
        finishSpeaking()
        speechQueue.clear()
        textToSpeechEngine.stop()
    }

    /**
     * Regenerates the agent reply [agentMessageId]: removes that reply and
     * everything after it in the current session (the same rewind
     * edit-and-resend performs), then re-runs the preceding user message
     * through the agent via [AgentLoop.retryRequest] — the existing path
     * that re-executes a persisted request without inserting a duplicate
     * user bubble, so the replacement answer is a genuinely new provider
     * response, never a copy of the old one.
     */
    fun regenerate(agentMessageId: String, context: Context) {
        viewModelScope.launch {
            val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
                ?: conversationRepository.ensureCurrentSessionId()
            val messages = conversationRepository.getMessages(sessionId).first()
            val agentIndex = messages.indexOfFirst {
                it.id == agentMessageId && it.sender == ChatMessage.Sender.AGENT
            }
            if (agentIndex < 0) return@launch
            val userMessage = messages.subList(0, agentIndex)
                .lastOrNull { it.sender == ChatMessage.Sender.USER } ?: return@launch
            agentLoop.cancelCurrentTask()
            conversationRepository.deleteMessageAndAfter(sessionId, agentMessageId)
            _taskSessionId.value = sessionId
            agentLoop.retryRequest(
                userMessage.id, sessionId, context,
                configOverrideFor(sessions.value.firstOrNull { it.id == sessionId })
            )
        }
    }

    override fun onCleared() {
        stopSpeaking()
        super.onCleared()
    }

    // ── Chat export (A11) ───────────────────────────────────────────────

    /**
     * Builds the export payload for [sessionId] from its real stored rows
     * (the same rows the chat screen renders), or null when the session no
     * longer exists or has no messages — callers must refuse the export in
     * that case instead of writing an empty file. Serialisation and PDF
     * rendering run off the main thread.
     */
    suspend fun buildChatExport(
        sessionId: String,
        format: com.opendroid.ai.data.export.ChatExportFormat
    ): com.opendroid.ai.data.export.ChatExportPayload? {
        val session = conversationRepository.sessions.first()
            .firstOrNull { it.id == sessionId } ?: return null
        val messages = conversationRepository.getMessages(sessionId).first()
        if (messages.isEmpty()) return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            when (format) {
                com.opendroid.ai.data.export.ChatExportFormat.MARKDOWN ->
                    com.opendroid.ai.data.export.ChatExportPayload(
                        fileName = com.opendroid.ai.data.export.ChatExporter
                            .suggestedFileName(session, format.extension),
                        mimeType = format.mimeType,
                        textContent = com.opendroid.ai.data.export.ChatExporter
                            .toMarkdown(session, messages),
                        binaryContent = null
                    )
                com.opendroid.ai.data.export.ChatExportFormat.PDF ->
                    com.opendroid.ai.data.export.ChatExportPayload(
                        fileName = com.opendroid.ai.data.export.ChatExporter
                            .suggestedFileName(session, format.extension),
                        mimeType = format.mimeType,
                        textContent = null,
                        binaryContent = com.opendroid.ai.data.export.ChatPdfExporter
                            .render(session, messages)
                    )
            }
        }
    }

    fun retryAfterChatError(context: Context) {
        val error = agentLoop.chatError.value ?: return
        when (error.primaryAction()) {
            ChatErrorPrimaryAction.RETRY -> {
                agentLoop.dismissChatError()
                // Re-execute the request the error actually describes, in ITS session -
                // never re-send the visible chat's last message, and never insert a
                // duplicate user bubble: the original message is already persisted.
                _taskSessionId.value = error.sessionId
                agentLoop.retryRequest(
                    error.requestId, error.sessionId, context,
                    configOverrideFor(sessions.value.firstOrNull { it.id == error.sessionId })
                )
            }
            else -> agentLoop.dismissChatError()
        }
    }

    // Session the most recently started task was pinned to. Set once, right when a task
    // is kicked off (sendMessage for a genuinely new query, approvePlan, or
    // editAndResend's resend) - mirroring AgentLoop's own "resolve the session once, at
    // task start" rule so [agentState], which is a single field shared by every chat,
    // can be attributed back to the chat it actually belongs to. See [visibleAgentState].
    private val _taskSessionId = MutableStateFlow<String?>(null)

    /**
     * [agentState], but forced to [AgentState.Idle] whenever it actually describes a
     * task pinned to a DIFFERENT chat than whichever one is current. Since a task now
     * keeps running (and writing) in the chat it started in even after the user
     * switches away, the shared [agentState] alone can't distinguish "busy in this
     * chat" from "busy in some other chat" - this is what any UI representing the
     * CURRENTLY VIEWED chat (thinking bubble, plan-approval prompt, orb pulse, status
     * text) must read instead of [agentState] directly, or it would misattribute a
     * background chat's activity to whatever the user happens to be looking at.
     */
    val visibleAgentState: StateFlow<AgentState> = combine(
        agentState, _taskSessionId, sessions
    ) { state, taskSessionId, sessionList ->
        val current = sessionList.firstOrNull { it.isCurrent }?.id
        if (taskSessionId == null || taskSessionId == current) state else AgentState.Idle
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AgentState.Idle
    )

    /**
     * Id of whichever chat has an actively in-flight task (thinking or executing a plan
     * step), or null when nothing is running anywhere. Unlike [visibleAgentState] this
     * is NOT scoped to the currently viewed chat - it drives the chat-picker's "still
     * running" indicator, which must stay visible for that chat even while the user has
     * switched away from it.
     */
    val runningSessionId: StateFlow<String?> = combine(agentState, _taskSessionId) { state, sessionId ->
        if (sessionId != null && (state is AgentState.Thinking || state is AgentState.ExecutingPlan)) {
            sessionId
        } else {
            null
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // Snapshots which chat is current right now and records it as the session the next
    // task will be pinned to. Reads the live-collected [sessions] snapshot for the same
    // reason [isCurrentSession] does - [sessions] is guaranteed to be actively collected
    // by ChatScreen, [currentSessionId] is not. Returns the same id so callers can pass
    // it straight through to AgentLoop.processQuery instead of it re-resolving "current"
    // itself at an arbitrary later moment.
    private fun pinTaskSessionSnapshot(): String? {
        val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
        _taskSessionId.value = sessionId
        return sessionId
    }

    // ── Composer attachments (B1) ─────────────────────────────────────
    // The pending attachment is a staged copy in app-private storage (see
    // AttachmentProcessor) - never a raw picker URI. The gate state is set
    // when the current model cannot take the attachment; the composer then
    // offers a model switch instead of silently dropping the file.

    private val _pendingAttachment = MutableStateFlow<PendingAttachment?>(null)

    /** The attachment staged for the next send, shown as a composer chip. */
    val pendingAttachment: StateFlow<PendingAttachment?> = _pendingAttachment

    private val _attachmentGate = MutableStateFlow<AttachmentGate?>(null)

    /** Non-null while the "current model can't take this" dialog is up. */
    val attachmentGate: StateFlow<AttachmentGate?> = _attachmentGate

    private val _attachmentError = MutableStateFlow<AttachmentSendError?>(null)

    /** Last attachment failure; [AttachmentSendError.draftText] lets the
     *  composer restore a draft whose send failed during preparation. */
    val attachmentError: StateFlow<AttachmentSendError?> = _attachmentError

    private var pendingCameraFile: java.io.File? = null

    /** Copies a picked photo/file into app-private staging for the chip. */
    fun stageAttachment(uri: Uri) {
        viewModelScope.launch {
            try {
                val staged = attachmentProcessor.stagePickedFile(uri)
                _pendingAttachment.value?.let { attachmentProcessor.discard(it) }
                _pendingAttachment.value = staged
            } catch (e: AttachmentException) {
                _attachmentError.value = AttachmentSendError(
                    e.message ?: "Couldn't attach that file.", draftText = null
                )
            }
        }
    }

    /**
     * Creates the camera capture target and returns the FileProvider URI to
     * hand the camera app; the photo lands directly in private staging.
     */
    fun beginCameraCapture(): Uri {
        val (file, uri) = attachmentProcessor.createCameraTarget()
        pendingCameraFile = file
        return uri
    }

    /** Settles a camera capture: success stages the photo, cancel cleans up. */
    fun onCameraCaptureResult(success: Boolean) {
        val file = pendingCameraFile
        pendingCameraFile = null
        if (!success || file == null) {
            file?.delete()
            return
        }
        val staged = attachmentProcessor.finishCameraCapture(file) ?: return
        _pendingAttachment.value?.let { attachmentProcessor.discard(it) }
        _pendingAttachment.value = staged
    }

    /** Removes the pending chip and deletes its staged copy. */
    fun clearPendingAttachment() {
        _pendingAttachment.value?.let { attachmentProcessor.discard(it) }
        _pendingAttachment.value = null
    }

    fun dismissAttachmentGate() {
        _attachmentGate.value = null
    }

    fun consumeAttachmentError() {
        _attachmentError.value = null
    }

    /**
     * The composer's send path when an attachment is pending. Returns false
     * (draft + chip kept) when there is no route right now or when the
     * current model cannot take the attachment - in the latter case
     * [attachmentGate] is set so the UI can offer a model switch. On true,
     * preparation runs async and the send dispatches once ready; a
     * preparation failure restores the chip and reports the draft back via
     * [attachmentError] so nothing the user typed is lost.
     */
    fun sendMessageWithAttachment(query: String, context: Context): Boolean {
        val pending = _pendingAttachment.value ?: return sendMessage(query, context)
        if (routingStatus.value is RoutingStatus.OfflineNoRoute) return false
        val effective = effectiveProviderModel.value
        val decision = VisionSupport.decide(pending.mimeType, effective.provider, effective.model)
        if (decision is VisionSupport.AttachmentDecision.Blocked) {
            _attachmentGate.value = AttachmentGate(
                attachmentName = pending.displayName,
                provider = effective.provider,
                model = effective.model,
                reason = decision.reason,
                draftText = query
            )
            return false
        }
        dispatchAttachmentSend(query, context, pending, decision)
        return true
    }

    private fun dispatchAttachmentSend(
        query: String,
        context: Context,
        pending: PendingAttachment,
        decision: VisionSupport.AttachmentDecision
    ) {
        val sessionId = pinTaskSessionSnapshot()
        val session = sessions.value.firstOrNull { it.id == sessionId }
        val override = configOverrideFor(session)
        _pendingAttachment.value = null
        viewModelScope.launch {
            try {
                val prepared = attachmentProcessor.prepare(pending, decision)
                val text = query.ifBlank { "Please see the attached file." } +
                    (prepared.textToAppend ?: "")
                agentLoop.processQuery(text, context, sessionId, override, prepared)
                attachmentProcessor.discard(pending)
            } catch (e: AttachmentException) {
                _pendingAttachment.value = pending
                _attachmentError.value = AttachmentSendError(
                    e.message ?: "Couldn't send that attachment.", draftText = query
                )
            }
        }
    }

    // ── Document scope (D4) ─────────────────────────────────────────
    // The "Ask my documents" chip: while on, plain sends are grounded in
    // the local knowledge base — retrieved, cited excerpts are appended
    // to the outgoing query (the same injection shape B1 uses for file
    // text). In-memory only; the scope is a per-session-of-use choice.

    private val _documentsScopeEnabled = MutableStateFlow(false)
    val documentsScopeEnabled: StateFlow<Boolean> = _documentsScopeEnabled

    fun setDocumentsScopeEnabled(enabled: Boolean) {
        _documentsScopeEnabled.value = enabled
    }

    /**
     * Sends [query] through the agent. Returns false without dispatching
     * when there is currently no route that could answer — offline with no
     * on-device model ready ([RoutingStatus.OfflineNoRoute], the same state
     * the header badge shows) — so the caller keeps the user's draft and
     * the inline offline notice explains, instead of a request hanging.
     */
    fun sendMessage(query: String, context: Context): Boolean {
        if (query.isBlank()) return false
        if (routingStatus.value is RoutingStatus.OfflineNoRoute) return false
        val sessionId = pinTaskSessionSnapshot()
        val session = sessions.value.firstOrNull { it.id == sessionId }
        val pendingImages = _pendingSharedImages.value
        if (pendingImages.isEmpty()) {
            if (_documentsScopeEnabled.value) {
                val override = configOverrideFor(session)
                viewModelScope.launch {
                    val contextBlock = runCatching {
                        knowledgeBaseRepository.buildChatContext(query)
                    }.getOrNull()
                    val grounded = if (contextBlock != null) {
                        "$query\n\n$contextBlock"
                    } else {
                        query
                    }
                    agentLoop.processQuery(grounded, context, sessionId, override)
                }
                return true
            }
            agentLoop.processQuery(query, context, sessionId, configOverrideFor(session))
            return true
        }
        // Shared images are pending (C2): the first rides this message
        // through the providers' existing imageBase64 channel; any others
        // are named in an appended note so the transcript stays honest about
        // what was shared. The private copies exist only to serve this send
        // and are deleted once it has consumed them. (Multi-image and
        // persisted attachments are B1's attachment-persistence work.)
        _pendingSharedImages.value = emptyList()
        val configOverride = configOverrideFor(session)
        viewModelScope.launch {
            val base64 = withContext(Dispatchers.IO) {
                sharedImageStore.loadBase64(pendingImages.first())
            }
            val note = SharedContent.additionalImagesNote(pendingImages)
            val finalQuery = if (note != null) "$query\n$note" else query
            agentLoop.processQuery(
                finalQuery, context, sessionId, configOverride,
                attachedImageBase64 = base64
            )
            withContext(Dispatchers.IO) {
                pendingImages.forEach { sharedImageStore.delete(it) }
            }
        }
        return true
    }

    /**
     * Edits a previously sent user message and resends it, DeepSeek-style: cancels any
     * in-flight agent task first (so a running/awaiting-input task can never race with
     * or swallow the resend), deletes that message and every message after it in the
     * current session so the conversation visibly rewinds, then sends the edited text
     * as a fresh query.
     */
    fun editAndResend(messageId: String, newText: String, context: Context) {
        if (newText.isBlank()) return
        // Same no-route guard as sendMessage: never rewind the conversation
        // for a resend that cannot be answered right now.
        if (routingStatus.value is RoutingStatus.OfflineNoRoute) return
        agentLoop.cancelCurrentTask()
        viewModelScope.launch {
            val sessionId = conversationRepository.ensureCurrentSessionId()
            conversationRepository.deleteMessageAndAfter(sessionId, messageId)
            _taskSessionId.value = sessionId
            val session = conversationRepository.sessions.first()
                .firstOrNull { it.id == sessionId }
            agentLoop.processQuery(newText, context, sessionId, configOverrideFor(session))
        }
    }

    fun approvePlan(context: Context, grants: Set<String> = emptySet()) {
        pinTaskSessionSnapshot()
        agentLoop.approveProposedPlan(context, grants)
    }

    /** Chip tap: Off <-> Auto only. YOLO is entered solely from Settings behind
     *  its warning dialog and exits to OFF from here (tapping the red chip is
     *  an explicit "get me out of YOLO", which is always safe). */
    // ── External launches (C1) ────────────────────────────────────────
    // The widget's mic button and the launcher's "Voice command" shortcut
    // land here (via ExternalIntentRouter → MainDashboard): ChatScreen
    // collects this and runs its normal permission-checked listening start,
    // so an outside tap behaves exactly like tapping the in-app mic.

    // Replay 1, deliberately: a widget tap can land while ChatScreen is not
    // composed yet (cold start through splash/lock), and the request must
    // survive until the collector appears. ChatScreen calls
    // [onVoiceInputRequestHandled] after acting on each emission, which
    // clears the replay slot so a later recomposition never replays a
    // stale "start listening".
    private val _voiceInputRequests = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    /** Requests to open voice input, emitted by external launches. */
    val voiceInputRequests: SharedFlow<Unit> = _voiceInputRequests.asSharedFlow()

    /** Asks the chat UI to start voice input as soon as it is composed. */
    fun requestVoiceInput() {
        _voiceInputRequests.tryEmit(Unit)
    }

    /** Clears the replayed voice request once ChatScreen has acted on it. */
    fun onVoiceInputRequestHandled() {
        _voiceInputRequests.resetReplayCache()
    }

    /**
     * Surfaces a morning briefing in the current chat: generates it through
     * [com.opendroid.ai.core.routine.HabitRoutineEngine] — the same call the
     * agent's GET_MORNING_BRIEFING action makes — and appends it as an agent
     * message, so the widget/shortcut "Brief me" lands on real content in a
     * real conversation instead of a toast that evaporates.
     */
    fun showMorningBriefing() {
        viewModelScope.launch {
            val briefing = runCatching { habitRoutineEngine.generateMorningBriefing() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return@launch
            conversationRepository.insertMessage(
                com.opendroid.ai.data.models.ChatMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    text = briefing,
                    sender = com.opendroid.ai.data.models.ChatMessage.Sender.AGENT
                )
            )
        }
    }

    fun cycleAutoMode() {
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                val next = when (current.resolvedAutoMode()) {
                    AutoMode.OFF -> AutoMode.AUTO
                    AutoMode.AUTO -> AutoMode.OFF
                    AutoMode.YOLO -> AutoMode.OFF
                }
                current.copy(autoMode = next, autoConfirmPlans = next == AutoMode.YOLO)
            }
        }
    }

    fun rejectPlan() {
        agentLoop.rejectProposedPlan()
    }

    /** Clears the messages of the CURRENT chat only - other chats are untouched. */
    fun clearChat() {
        agentLoop.cancelCurrentTask()
        viewModelScope.launch {
            conversationRepository.clearCurrentSession()
        }
    }

    /**
     * Starts a brand-new, empty chat and switches to it. Deliberately does NOT cancel
     * whatever the agent is currently doing: since a task's session is pinned once at
     * its start and threaded through explicitly, it keeps running - and keeps writing
     * its replies - in the chat it was launched from, not wherever the user navigates
     * to afterwards. [runningSessionId] / [visibleAgentState] are how the UI stays
     * truthful about that chat still being busy while this one is not.
     */
    fun newChat() {
        viewModelScope.launch {
            conversationRepository.createSession()
        }
    }

    /**
     * Switches the active chat; [conversationHistory] re-collects for the new session.
     * Deliberately does NOT cancel an in-flight task for the same reason [newChat] does
     * not - see its doc comment.
     */
    fun switchToSession(sessionId: String) {
        if (isCurrentSession(sessionId)) return
        viewModelScope.launch {
            conversationRepository.switchToSession(sessionId)
        }
    }

    /** Renames a chat without changing which one is current. */
    fun renameSession(sessionId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            conversationRepository.renameSession(sessionId, title.trim())
        }
    }

    /**
     * Deletes a chat and its messages. If it was the currently open chat, OR it is
     * whichever chat [runningSessionId] says has a task actively writing into it right
     * now, that task is cancelled first - deleting it out from under a still-running
     * task would otherwise leave that task writing into a session that no longer
     * exists. The repository guarantees another session becomes current - the user is
     * never left with no chat selected.
     */
    fun deleteChat(sessionId: String) {
        if (isCurrentSession(sessionId) || runningSessionId.value == sessionId) {
            agentLoop.cancelCurrentTask()
        }
        agentLoop.forgetSession(sessionId)
        viewModelScope.launch {
            conversationRepository.deleteSession(sessionId)
            conversationRepository.ensureCurrentSessionId()
        }
    }

    // Reads the live-collected [sessions] snapshot rather than [currentSessionId].value:
    // currentSessionId is exposed for callers that actually collect it, but nothing in
    // this ViewModel guarantees it is being collected, and an un-collected
    // WhileSubscribed StateFlow never advances past its initial value. [sessions] is
    // always actively collected by ChatScreen (it drives the chat picker itself), so by
    // the time a user can tap switch/delete on a session row, its value is current.
    private fun isCurrentSession(sessionId: String): Boolean =
        sessions.value.any { it.id == sessionId && it.isCurrent }

    /**
     * Cancels whatever the agent is currently doing without touching the
     * conversation history. Shared by the Plan tab and the chat UI's stop control.
     */
    fun stopTask() {
        agentLoop.cancelCurrentTask()
    }
}

/** How long a fetched provider model list stays fresh for the picker (matches Settings). */
private const val MODEL_CACHE_TTL_MS = 60 * 60 * 1000L

/**
 * B1 capability gate: the pending attachment cannot go to the model this
 * chat is currently resolved to. The composer shows [reason] and offers a
 * model switch; the draft and the attachment chip stay untouched.
 */
data class AttachmentGate(
    val attachmentName: String,
    val provider: String,
    val model: String,
    val reason: String,
    val draftText: String
)

/**
 * B1 attachment failure (staging or preparation). [draftText] is the
 * composer text of the failed send, when there was one, so the UI can put
 * it back instead of losing what the user typed.
 */
data class AttachmentSendError(
    val message: String,
    val draftText: String?
)
