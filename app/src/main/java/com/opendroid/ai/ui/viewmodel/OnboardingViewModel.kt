package com.opendroid.ai.ui.viewmodel

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.llm.ConnectionTestPlanner
import com.opendroid.ai.core.llm.ConnectionTestState
import com.opendroid.ai.core.llm.DownloadFit
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ModelDownloadStartResult
import com.opendroid.ai.core.llm.OnDeviceBackend
import com.opendroid.ai.core.llm.OnDeviceModelRegistry
import com.opendroid.ai.core.llm.OnDeviceModelSpec
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.llm.ProviderSetupPolicy
import com.opendroid.ai.core.llm.ProviderSetupReadiness
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.RetryPolicy
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.SecretRegistry
import com.opendroid.ai.core.llm.providers.GemmaProvider
import com.opendroid.ai.core.security.LegacyPreferenceMigration
import com.opendroid.ai.core.security.ProfileStoreResult
import com.opendroid.ai.core.security.UserProfile
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.core.settings.AppSettingsStore
import com.opendroid.ai.data.db.entities.ModelStatus
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.selectedModelFor
import com.opendroid.ai.data.models.withActiveProvider
import com.opendroid.ai.data.models.withSelectedModel
import com.opendroid.ai.data.repository.ModelRepository
import com.opendroid.ai.data.repository.ProviderCredentialPersistenceState
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @param profileMustBeReentered the previous profile could not be decrypted; the screen explains
 *   why the fields are blank instead of silently losing the user's details.
 * @param storageError the last save did not durably commit, so the user can retry.
 */
data class OnboardingUiState(
    val isLoading: Boolean = true,
    val name: String = "",
    val dateOfBirth: String = "",
    val profileMustBeReentered: Boolean = false,
    val storageError: Boolean = false
)

/**
 * State of the onboarding PROVIDER stage (A10): three no-key-or-BYOK setup
 * cards over the app's real provider state. Everything here is a projection of
 * the same stores Settings uses — the config flow, the on-device model table,
 * and the AI Core probe — so the stage can never promise a setup the rest of
 * the app disagrees with.
 *
 * @param canAnswer live readiness from [ProviderSetupReadiness]; true when a
 *   provider was already configured before this stage (or one just succeeded).
 * @param onDeviceDone a no-key on-device path completed in this session
 *   (AI Core enabled, LiteRT model ready, or its download queued — the checklist
 *   counts a queued download as configured; the banner logic keeps tracking
 *   the real state afterwards).
 */
data class ProviderSetupUiState(
    val isLoading: Boolean = true,
    val canAnswer: Boolean = false,
    val activeProviderName: String = "",
    // On-device card
    val aiCoreAvailable: Boolean = false,
    val aiCoreSpec: OnDeviceModelSpec? = null,
    val liteRtSpec: OnDeviceModelSpec? = null,
    val liteRtStatus: ModelStatus? = null,
    val liteRtProgress: Int = 0,
    val freeBytes: Long = 0L,
    val totalStorageBytes: Long = 0L,
    val downloadFit: DownloadFit = DownloadFit.Fits,
    val onDeviceBusy: Boolean = false,
    val onDeviceError: String? = null,
    val onDeviceDone: Boolean = false,
    // Free Gemini key card
    val geminiKey: String = "",
    val geminiBusy: Boolean = false,
    val geminiError: String? = null,
    val geminiResult: ConnectionTestState? = null,
    val geminiDone: Boolean = false,
    // BYOK / own-server card
    val byokProvider: String = "OpenAI",
    val byokKey: String = "",
    val byokEndpoint: String = "",
    val byokModel: String = "",
    val byokBusy: Boolean = false,
    val byokError: String? = null,
    val byokResult: ConnectionTestState? = null,
    val byokDone: Boolean = false
)

/** Owns the onboarding profile so no Composable touches encrypted storage directly. */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userProfileStore: UserProfileStore,
    private val appSettingsStore: AppSettingsStore,
    private val legacyPreferenceMigration: LegacyPreferenceMigration,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val gemmaProvider: GemmaProvider,
    private val providerSetupReadiness: ProviderSetupReadiness,
    private val llmProviderFactory: Lazy<LLMProviderFactory>,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = mutableUiState.asStateFlow()

    private val mutableProviderState = MutableStateFlow(
        ProviderSetupUiState(
            aiCoreSpec = OnDeviceModelRegistry.recommendedFor(OnDeviceBackend.AI_CORE),
            liteRtSpec = OnDeviceModelRegistry.recommendedFor(OnDeviceBackend.LITERT_LM)
        )
    )
    val providerSetupState: StateFlow<ProviderSetupUiState> = mutableProviderState.asStateFlow()

    init {
        viewModelScope.launch {
            // A failure here must still leave usable, blank fields rather than a stuck spinner.
            mutableUiState.value = runCatching {
                legacyPreferenceMigration.await()
                withContext(Dispatchers.IO) { loadProfile() }
            }.getOrElse { throwable ->
                Log.e(TAG, "Onboarding profile load failed", throwable)
                OnboardingUiState(isLoading = false, storageError = true)
            }
        }
        viewModelScope.launch {
            refreshStorageStats()
        }
        viewModelScope.launch {
            val available = runCatching { gemmaProvider.isAvailable() }.getOrDefault(false)
            mutableProviderState.update { it.copy(aiCoreAvailable = available) }
        }
        viewModelScope.launch {
            combine(settingsRepository.llmConfig, modelRepository.allModelsFlow) { config, models ->
                config to models
            }.collect { (config, models) ->
                mutableProviderState.update { state ->
                    val liteRt = state.liteRtSpec?.let { spec ->
                        models.firstOrNull { it.id == spec.id }
                    }
                    state.copy(
                        activeProviderName = ProviderCatalog.canonicalName(config.activeProvider),
                        liteRtStatus = liteRt?.status,
                        liteRtProgress = liteRt?.downloadProgress ?: 0,
                        // A download that lands while the stage is open completes
                        // the on-device path on its own.
                        onDeviceDone = state.onDeviceDone ||
                            liteRt?.status == ModelStatus.READY,
                        downloadFit = state.downloadFit()
                    )
                }
            }
        }
        viewModelScope.launch {
            providerSetupReadiness.canAnswer.collect { canAnswer ->
                mutableProviderState.update { it.copy(canAnswer = canAnswer, isLoading = false) }
            }
        }
    }

    /** The fit gate for the recommended LiteRT download, from live state. */
    private fun ProviderSetupUiState.downloadFit(): DownloadFit =
        ProviderSetupPolicy.assessDownloadFit(
            modelBytes = liteRtSpec?.expectedSize ?: 0L,
            freeBytes = freeBytes,
            totalStorageBytes = totalStorageBytes
        )

    /**
     * Re-reads the data volume — the same StatFs source [ModelRepository]'s
     * funnel gates on — and refreshes the free/total bytes and the fit verdict
     * in state, so the panel's numbers, and the low-space warning that doubles
     * as the user's download confirmation, reflect the device right now
     * instead of the moment onboarding opened.
     */
    private suspend fun refreshStorageStats() {
        val stat = withContext(Dispatchers.IO) {
            runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        } ?: return
        mutableProviderState.update { state ->
            val refreshed = state.copy(
                freeBytes = stat.availableBytes,
                totalStorageBytes = stat.totalBytes
            )
            refreshed.copy(downloadFit = refreshed.downloadFit())
        }
    }

    fun onNameChange(value: String) {
        mutableUiState.value = mutableUiState.value.copy(name = value, storageError = false)
    }

    fun onDateOfBirthChange(value: String) {
        mutableUiState.value = mutableUiState.value.copy(dateOfBirth = value, storageError = false)
    }

    /** Invokes [onSaved] only after the profile is durably encrypted at rest. */
    fun saveProfile(onSaved: () -> Unit) {
        val state = mutableUiState.value
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    userProfileStore.write(
                        UserProfile(name = state.name.trim(), dateOfBirth = state.dateOfBirth.trim())
                    )
                }
            }.getOrElse { throwable ->
                Log.e(TAG, "Onboarding profile save failed", throwable)
                ProfileStoreResult.StorageUnavailable
            }
            if (result is ProfileStoreResult.Success) {
                mutableUiState.value = mutableUiState.value.copy(
                    profileMustBeReentered = false,
                    storageError = false
                )
                onSaved()
            } else {
                mutableUiState.value = mutableUiState.value.copy(storageError = true)
            }
        }
    }

    /** Marks onboarding complete and invokes [onCompleted] regardless of the settings write. */
    fun completeOnboarding(onCompleted: () -> Unit) {
        viewModelScope.launch {
            // A failed flag write only means the user sees onboarding again; the profile they
            // just entered is already stored, so it is not worth blocking them here.
            runCatching { withContext(Dispatchers.IO) { appSettingsStore.setOnboardingCompleted(true) } }
                .onFailure { throwable -> Log.e(TAG, "Onboarding-completed flag write failed", throwable) }
            onCompleted()
        }
    }

    // ── PROVIDER stage: on-device card ─────────────────────────────────────

    /** Enables the AI Core backend: no download, no key — just point the config at it. */
    fun enableAiCore() {
        val spec = mutableProviderState.value.aiCoreSpec ?: return
        mutableProviderState.update { it.copy(onDeviceBusy = true, onDeviceError = null) }
        viewModelScope.launch {
            val saved = settingsRepository.updateConfig { current ->
                current
                    .withActiveProvider(ProviderCatalog.ON_DEVICE)
                    .withSelectedModel(ProviderCatalog.ON_DEVICE, spec.id)
            }
            mutableProviderState.update { state ->
                if (saved == ProviderCredentialPersistenceState.Ready) {
                    state.copy(onDeviceBusy = false, onDeviceDone = true)
                } else {
                    state.copy(
                        onDeviceBusy = false,
                        onDeviceError = "On-device AI could not be saved. Please try again."
                    )
                }
            }
        }
    }

    /**
     * Queues the recommended LiteRT model download through the existing
     * [ModelRepository] funnel (WorkManager, integrity-checked) and points the
     * config at it, so chat uses the model the moment the download lands.
     * Blocked up front when the fit check says the file cannot fit.
     *
     * When the fit lands in the low-space warning band, the panel has been
     * showing that warning right above the button, so the tap itself is the
     * confirmation the funnel requires — it is threaded through as
     * `fitConfirmed` rather than dropped. The funnel re-evaluates the fit
     * fresh and reports a typed result; the on-device path is only marked done
     * when the download was actually enqueued, and a refusal is surfaced
     * verbatim instead of being reported as success.
     */
    fun downloadLiteRtModel() {
        val state = mutableProviderState.value
        val spec = state.liteRtSpec ?: return
        if (state.downloadFit == DownloadFit.DoesNotFit) {
            mutableProviderState.update {
                it.copy(onDeviceError = "There isn't enough free space on this device for that download.")
            }
            return
        }
        // The panel's low-space warning is on screen for exactly this fit
        // state; tapping Download is the user confirming it.
        val fitConfirmed = state.downloadFit == DownloadFit.FitsWithLowSpaceWarning
        mutableProviderState.update { it.copy(onDeviceBusy = true, onDeviceError = null) }
        viewModelScope.launch {
            // Keep the panel's storage numbers (and warning) current, so if
            // the funnel still refuses, the state behind the retry is real.
            refreshStorageStats()
            val ramFailure = runCatching {
                OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, spec)
            }.exceptionOrNull()
            if (ramFailure != null) {
                mutableProviderState.update {
                    it.copy(
                        onDeviceBusy = false,
                        onDeviceError = ramFailure.message ?: "This device doesn't have enough memory for that model."
                    )
                }
                return@launch
            }
            when (val result = modelRepository.startDownload(spec, fitConfirmed)) {
                ModelDownloadStartResult.Enqueued -> {
                    val saved = settingsRepository.updateConfig { current ->
                        current
                            .withActiveProvider(ProviderCatalog.ON_DEVICE)
                            .withSelectedModel(ProviderCatalog.ON_DEVICE, spec.id)
                    }
                    mutableProviderState.update {
                        if (saved == ProviderCredentialPersistenceState.Ready) {
                            // Queued counts as configured (checklist A10); live readiness
                            // keeps tracking the download through ProviderSetupReadiness.
                            it.copy(onDeviceBusy = false, onDeviceDone = true)
                        } else {
                            it.copy(
                                onDeviceBusy = false,
                                onDeviceError = "The download started, but the model could not be selected. " +
                                    "You can pick it later in Settings."
                            )
                        }
                    }
                }
                is ModelDownloadStartResult.Refused -> {
                    // The funnel refused (storage shrank, integrity metadata
                    // missing, …). Never mark the path done: show the reason.
                    mutableProviderState.update {
                        it.copy(onDeviceBusy = false, onDeviceError = result.reason)
                    }
                }
            }
        }
    }

    /**
     * Resumes a paused LiteRT download from the same card. The low-space
     * warning renders above the Resume button for the same fit state, so the
     * tap carries the same confirmation as a fresh download; a refusal from
     * the funnel is surfaced on the card instead of failing silently.
     */
    fun resumeLiteRtModel() {
        val state = mutableProviderState.value
        val spec = state.liteRtSpec ?: return
        val fitConfirmed = state.downloadFit == DownloadFit.FitsWithLowSpaceWarning
        viewModelScope.launch {
            refreshStorageStats()
            when (val result = modelRepository.resumeDownload(spec, fitConfirmed)) {
                // The model table flow takes over the card from here.
                ModelDownloadStartResult.Enqueued -> Unit
                is ModelDownloadStartResult.Refused ->
                    mutableProviderState.update { it.copy(onDeviceError = result.reason) }
            }
        }
    }

    // ── PROVIDER stage: free Gemini key card ───────────────────────────────

    fun onGeminiKeyChange(value: String) {
        mutableProviderState.update { it.copy(geminiKey = value, geminiError = null, geminiResult = null) }
    }

    /**
     * Saves the pasted Gemini key through [SettingsRepository] — which writes
     * it into the Keystore-backed credential store, never plaintext prefs —
     * makes Gemini the active provider, then validates with the same
     * connection-test probe Settings uses.
     */
    fun saveAndTestGeminiKey() {
        val key = mutableProviderState.value.geminiKey.trim()
        if (key.isBlank()) {
            mutableProviderState.update {
                it.copy(
                    geminiResult = ConnectionTestPlanner.stamp(
                        ConnectionTestState.ConfigMissing("Google Gemini", LLMError.AuthMissing, 0L),
                        System.currentTimeMillis()
                    )
                )
            }
            return
        }
        mutableProviderState.update { it.copy(geminiBusy = true, geminiError = null, geminiResult = null) }
        viewModelScope.launch {
            val saved = settingsRepository.updateConfig { current ->
                current
                    .copy(apiKeys = current.apiKeys + ("Google Gemini" to key))
                    .withActiveProvider("Google Gemini")
            }
            if (saved != ProviderCredentialPersistenceState.Ready) {
                mutableProviderState.update {
                    it.copy(
                        geminiBusy = false,
                        geminiError = "That key could not be stored securely on this device. Please try again."
                    )
                }
                return@launch
            }
            val result = probe("Google Gemini", secret = key)
            mutableProviderState.update {
                it.copy(
                    geminiBusy = false,
                    geminiResult = result,
                    geminiDone = result is ConnectionTestState.Connected
                )
            }
        }
    }

    // ── PROVIDER stage: BYOK / own-server card ─────────────────────────────

    fun onByokProviderChange(providerDisplayName: String) {
        mutableProviderState.update {
            if (it.byokProvider == providerDisplayName) {
                it.copy(byokError = null, byokResult = null, byokDone = false)
            } else {
                // Clear the shared key/endpoint/model fields on a real
                // provider switch: they used to survive, so a key or URL
                // typed for one provider was displayed for — and on
                // "Save & test" saved under — a different provider (an
                // OpenAI key stored as the Anthropic key).
                it.copy(
                    byokProvider = providerDisplayName,
                    byokKey = "",
                    byokEndpoint = "",
                    byokModel = "",
                    byokError = null,
                    byokResult = null,
                    byokDone = false
                )
            }
        }
    }

    fun onByokKeyChange(value: String) {
        mutableProviderState.update { it.copy(byokKey = value, byokError = null, byokResult = null) }
    }

    fun onByokEndpointChange(value: String) {
        mutableProviderState.update { it.copy(byokEndpoint = value, byokError = null, byokResult = null) }
    }

    fun onByokModelChange(value: String) {
        mutableProviderState.update { it.copy(byokModel = value, byokError = null, byokResult = null) }
    }

    /**
     * The Settings provider matrix in miniature: a key for any cloud provider,
     * or an Ollama / Copilot / custom OpenAI-compatible endpoint. Saved through
     * the same [SettingsRepository] door as Settings, then probed the same way.
     */
    fun saveAndTestByok() {
        val state = mutableProviderState.value
        val provider = ProviderCatalog.canonicalName(state.byokProvider)
        val key = state.byokKey.trim()
        val endpoint = state.byokEndpoint.trim()
        val model = state.byokModel.trim()

        val validationError = when (provider) {
            "Ollama", "Copilot API" ->
                if (endpoint.isBlank()) "Enter the server URL first." else null
            "Custom OpenAI Compatible" -> when {
                endpoint.isBlank() -> "Enter the server URL first."
                key.isBlank() -> "Enter an API key first."
                else -> null
            }
            else -> if (key.isBlank()) "Enter an API key first." else null
        }
        if (validationError != null) {
            mutableProviderState.update { it.copy(byokError = validationError) }
            return
        }

        mutableProviderState.update { it.copy(byokBusy = true, byokError = null, byokResult = null) }
        viewModelScope.launch {
            val saved = settingsRepository.updateConfig { current ->
                val updated = when (provider) {
                    "Ollama" -> current.copy(ollamaUrl = endpoint)
                    "Copilot API" -> current.copy(copilotUrl = endpoint)
                    "Custom OpenAI Compatible" -> current.copy(
                        customEndpoints = current.customEndpoints + (provider to endpoint),
                        apiKeys = current.apiKeys + (provider to key)
                    )
                    else -> current.copy(apiKeys = current.apiKeys + (provider to key))
                }
                val activated = updated.withActiveProvider(provider)
                if (provider == "Ollama" && model.isNotBlank()) {
                    activated.withSelectedModel(provider, model)
                } else {
                    activated
                }
            }
            if (saved != ProviderCredentialPersistenceState.Ready) {
                mutableProviderState.update {
                    it.copy(byokBusy = false, byokError = "That could not be saved securely. Please try again.")
                }
                return@launch
            }
            val result = probe(provider, secret = key, endpoint = endpoint)
            mutableProviderState.update {
                it.copy(
                    byokBusy = false,
                    byokResult = result,
                    byokDone = result is ConnectionTestState.Connected
                )
            }
        }
    }

    /**
     * The connection test Settings runs, unchanged: the planner's local gap
     * check first, then a real 'ping' completion through the provider factory.
     * The candidate secret/endpoint are registered with [SecretRegistry] for
     * the duration so a failure can never echo them back in an error string.
     */
    private suspend fun probe(
        providerName: String,
        secret: String,
        endpoint: String = ""
    ): ConnectionTestState {
        val provider = ProviderCatalog.canonicalName(providerName)
        val config = settingsRepository.llmConfig.first()
        val model = config.selectedModelFor(provider)
        ConnectionTestPlanner.configurationGap(config, provider)?.let { gap ->
            return ConnectionTestPlanner.stamp(gap, System.currentTimeMillis())
        }
        val registrations = buildList {
            if (secret.isNotBlank()) add(SecretRegistry.register(secret))
            if (endpoint.isNotBlank()) add(SecretRegistry.register(endpoint))
        }
        return try {
            val response = llmProviderFactory.get().getProviderByName(provider).complete(
                LLMRequest(
                    systemPrompt = "You are a speed test server. Respond with 'pong'.",
                    messages = listOf(
                        ChatMessage(id = "1", text = "ping", sender = ChatMessage.Sender.USER)
                    ),
                    responseFormat = ResponseFormat.TEXT,
                    retryPolicy = RetryPolicy.NONE
                )
            )
            ConnectionTestPlanner.success(
                provider = provider,
                model = response.model.ifBlank { model },
                latencyMs = response.latencyMs,
                testedAtMillis = System.currentTimeMillis()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionTestPlanner.fromException(
                provider = provider,
                model = model,
                throwable = e,
                testedAtMillis = System.currentTimeMillis()
            )
        } finally {
            registrations.asReversed().forEach(AutoCloseable::close)
        }
    }

    private fun loadProfile(): OnboardingUiState = when (val result = userProfileStore.read()) {
        is ProfileStoreResult.Success -> {
            val profile = result.value
            OnboardingUiState(
                isLoading = false,
                name = profile?.name.orEmpty(),
                dateOfBirth = profile?.dateOfBirth.orEmpty(),
                profileMustBeReentered = profile == null && appSettingsStore.isOnboardingCompleted()
            )
        }
        // Never fall back to a plaintext copy; ask for the details again instead.
        ProfileStoreResult.ProfileMustBeReentered -> {
            // Clear the unusable key material so the profile the user re-enters can be stored.
            userProfileStore.resetForReentry()
            OnboardingUiState(isLoading = false, profileMustBeReentered = true)
        }
        ProfileStoreResult.StorageUnavailable ->
            OnboardingUiState(isLoading = false, storageError = true)
    }

    private companion object {
        const val TAG = "OnboardingViewModel"
    }
}
