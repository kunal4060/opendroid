package com.opendroid.ai.core.backup

import android.content.Context
import androidx.room.withTransaction
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.core.security.SensitiveMemoryStore
import com.opendroid.ai.core.settings.AppSettingsStore
import com.opendroid.ai.data.backup.AppSettingsBackup
import com.opendroid.ai.data.backup.BackupPayload
import com.opendroid.ai.data.backup.BackupSummary
import com.opendroid.ai.data.backup.ChatSessionBackup
import com.opendroid.ai.data.backup.ConversationBackup
import com.opendroid.ai.data.backup.CredentialsBackup
import com.opendroid.ai.data.backup.MacroBackup
import com.opendroid.ai.data.backup.MemoryBackup
import com.opendroid.ai.data.backup.PlanBackup
import com.opendroid.ai.data.backup.RoutineBackup
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.RoutineStatus
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Orchestrates the encrypted backup & restore (feature B2).
 *
 * Export: snapshots the real stores — chat sessions + messages, memories
 * (which includes the knowledge-graph rows verbatim), macros, plans,
 * routines, the Settings DataStore and the AppSettingsStore's semantic
 * values — into one [BackupPayload], serialises it to JSON and streams it
 * through [BackupCrypto] (PBKDF2 + AES-256-GCM) into the stream the caller
 * opened from the Storage Access Framework picker. Provider credentials
 * and sensitive memories are included **only** when the user opts in, read
 * decrypted through the stores' own APIs.
 *
 * Import is deliberately two-phase so a bad file can never half-restore:
 *  1. [inspectBackup] decrypts the chosen file into a temp file in
 *     `cacheDir`, parses and validates the whole payload there (format
 *     version gate, DB version gate, JSON well-formedness, session
 *     current-flag sanity) and returns a [BackupSummary]. Nothing in the
 *     live stores has been touched at this point; a wrong passphrase fails
 *     here — in the GCM tag check — with existing data untouched.
 *  2. [applyPendingRestore] re-reads the validated temp file and applies
 *     it: credentials first (through the stores' write APIs, so they are
 *     re-wrapped under this device's Keystore key — an early failure
 *     aborts before any user data is touched), then the Room sections in
 *     a single transaction (delete-then-insert per backed-up table, other
 *     tables untouched), then the settings sections. The temp file is
 *     always deleted afterwards.
 *
 * Restore semantics are **Replace** for every section the backup carries;
 * the UI says so and asks for a second confirmation before this runs.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: OpenDroidDatabase,
    private val settingsRepository: SettingsRepository,
    private val providerCredentialStore: ProviderCredentialStore,
    private val sensitiveMemoryStore: SensitiveMemoryStore,
    private val appSettingsStore: AppSettingsStore
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Decrypted, validated payload waiting for the user's restore confirmation. */
    private var pendingTempFile: File? = null
    private var pendingPayload: BackupPayload? = null

    /** `opendroid-backup-<yyyy-MM-dd>.odbak`, as suggested to the SAF create-document picker. */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        return "opendroid-backup-$stamp.odbak"
    }

    /**
     * Builds the payload from the live stores and writes it, encrypted with
     * [passphrase], to [output] (a SAF destination opened by the caller,
     * which keeps ownership of the stream). Returns what was written.
     */
    suspend fun exportBackup(
        passphrase: CharArray,
        includeCredentials: Boolean,
        output: OutputStream
    ): BackupSummary = withContext(Dispatchers.IO) {
        val payload = buildPayload(includeCredentials)
        val header = BackupHeader(
            formatVersion = BackupCrypto.FORMAT_VERSION,
            createdAt = payload.exportedAt,
            appVersion = payload.appVersion,
            dbVersion = payload.dbVersion,
            // Placeholders — BackupCrypto fills in the real KDF values it generated.
            kdfAlgorithm = "",
            kdfIterations = 0,
            saltBase64 = "",
            ivBase64 = "",
            sections = payload.sections()
        )
        val payloadJson = json.encodeToString(BackupPayload.serializer(), payload)
        BackupCrypto.encrypt(passphrase, header, output) { cipherSink ->
            // Feed the JSON through in slices; the sink chunks it for the cipher.
            val bytes = payloadJson.toByteArray(Charsets.UTF_8)
            var offset = 0
            while (offset < bytes.size) {
                val take = minOf(SLICE_BYTES, bytes.size - offset)
                cipherSink.write(bytes, offset, take)
                offset += take
            }
        }
        payload.summary()
    }

    /**
     * Phase 1 of restore: decrypt [input] (a SAF source opened by the
     * caller) to a temp file, validate the payload completely, and stash it
     * as the pending restore. Throws [BackupCryptoException] for a wrong
     * passphrase / damaged / too-new file, and [BackupRestoreException] for
     * a well-decrypted payload that fails validation. Live data is never
     * touched here. Any previously pending restore is discarded.
     */
    suspend fun inspectBackup(
        passphrase: CharArray,
        input: InputStream
    ): BackupSummary = withContext(Dispatchers.IO) {
        discardPending()
        val tempFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, context.cacheDir)
        try {
            val header = tempFile.outputStream().buffered().use { plaintextOut ->
                BackupCrypto.decrypt(passphrase, input, plaintextOut)
            }
            val payload = parsePayload(tempFile)
            validatePayload(payload, header)
            pendingTempFile = tempFile
            pendingPayload = payload
            payload.summary(headerCreatedAt = header.createdAt)
        } catch (failure: Exception) {
            tempFile.delete()
            throw failure
        }
    }

    /**
     * Phase 2 of restore: applies the payload validated by [inspectBackup].
     * Order matters — credentials first (abort early, before user data is
     * touched), then one Room transaction, then settings. A failure part-way
     * is reported as a failure with the failing area named, never dressed up
     * as success; the Room sections themselves are atomic.
     */
    suspend fun applyPendingRestore(): BackupSummary = withContext(Dispatchers.IO) {
        val payload = pendingPayload
            ?: throw BackupRestoreException("There is no validated backup waiting to be restored.")
        try {
            payload.credentials?.let { restoreCredentials(it) }
            applyRoomSections(payload)
            payload.settings?.let { settingsRepository.restoreSettingsFromBackup(it) }
            payload.appSettings?.let { restoreAppSettings(it) }
            payload.summary()
        } finally {
            discardPending()
        }
    }

    /** Drops a validated-but-unapplied restore and deletes its temp file. */
    fun discardPending() {
        pendingTempFile?.delete()
        pendingTempFile = null
        pendingPayload = null
    }

    fun hasPendingRestore(): Boolean = pendingPayload != null

    // ── Export ──────────────────────────────────────────────────────────

    private suspend fun buildPayload(includeCredentials: Boolean): BackupPayload {
        val sessions = database.chatSessionDao().getAllSessions().first()
        val conversations = database.conversationDao().getAllConversations().first()
        val memories = database.memoryDao().getAllMemories()
        val macros = database.macroDao().getAllMacros()
        val plans = database.planDao().getAllPlans()
        val routines = database.habitDao().getAllRoutines()
        return BackupPayload(
            formatVersion = BackupCrypto.FORMAT_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            dbVersion = currentDbVersion(),
            sessions = sessions.map { ChatSessionBackup.from(it) },
            conversations = conversations.map { ConversationBackup.from(it) },
            memories = memories.map { MemoryBackup.from(it) },
            macros = macros.map { MacroBackup.from(it) },
            plans = plans.map { PlanBackup.from(it) },
            routines = routines.map { RoutineBackup.from(it) },
            settings = settingsRepository.snapshotSettingsForBackup(),
            appSettings = AppSettingsBackup(
                onboardingCompleted = appSettingsStore.isOnboardingCompleted(),
                huggingFaceLastVerified = appSettingsStore.huggingFaceLastVerified()
            ),
            credentials = if (includeCredentials) readCredentials() else null
        )
    }

    private fun readCredentials(): CredentialsBackup {
        val apiKeys = when (val result = providerCredentialStore.readProviderApiKeys()) {
            is CredentialStoreResult.Success -> result.value
            else -> throw BackupRestoreException(
                "Your stored API keys could not be read, so the backup was not made. " +
                    "Re-enter your provider keys in Settings and try again."
            )
        }
        fun readOptional(id: ProviderCredentialId): String? =
            (providerCredentialStore.read(id) as? CredentialStoreResult.Success)?.value
        return CredentialsBackup(
            providerApiKeys = apiKeys,
            elevenLabsApiKey = readOptional(ProviderCredentialId.ElevenLabsApiKey),
            huggingFaceToken = readOptional(ProviderCredentialId.HuggingFaceToken),
            sensitiveMemories = sensitiveMemoryStore.getAllDecrypted()
        )
    }

    // ── Restore ─────────────────────────────────────────────────────────

    private fun parsePayload(tempFile: File): BackupPayload {
        val text = tempFile.readText(Charsets.UTF_8)
        return try {
            json.decodeFromString(BackupPayload.serializer(), text)
        } catch (failure: Exception) {
            throw BackupRestoreException(
                "The backup's contents are damaged and cannot be restored.", failure
            )
        }
    }

    private fun validatePayload(payload: BackupPayload, header: BackupHeader) {
        if (payload.formatVersion > BackupCrypto.FORMAT_VERSION) {
            throw BackupCryptoException.UnsupportedVersion(
                "This backup was made by a newer version of OpenDroid. Update the app to restore it."
            )
        }
        val currentDb = currentDbVersion()
        if (payload.dbVersion > currentDb) {
            throw BackupRestoreException(
                "This backup was made by a newer version of OpenDroid (database ${payload.dbVersion} " +
                    "> $currentDb). Update the app to restore it."
            )
        }
        // Every conversation must belong to a session the backup also carries;
        // otherwise the restored chats would point at sessions that do not exist.
        val sessionIds = payload.sessions.mapTo(HashSet()) { it.id }
        val orphan = payload.conversations.firstOrNull { it.sessionId !in sessionIds }
        if (orphan != null) {
            throw BackupRestoreException(
                "The backup is inconsistent (a chat message has no chat). It was not restored."
            )
        }
        if (header.dbVersion != payload.dbVersion) {
            throw BackupRestoreException("The backup is inconsistent and cannot be restored.")
        }
        // Enum fields travel as raw strings and toEntity() writes them
        // verbatim into Room. An unknown value means the backup was made by
        // a build whose model this one doesn't understand (or was edited by
        // hand); restoring it would poison every later read of that type.
        // The repositories now parse these defensively (audit-4 #1), but the
        // restore is refused here so the user hears about it instead of
        // silently getting degraded rows.
        if (payload.plans.any { runCatching { PlanStatus.valueOf(it.status) }.isFailure }) {
            throw BackupRestoreException(
                "The backup is inconsistent (a plan has an unknown status). It was not restored."
            )
        }
        if (payload.memories.any { runCatching { MemoryType.valueOf(it.type) }.isFailure }) {
            throw BackupRestoreException(
                "The backup is inconsistent (a memory has an unknown type). It was not restored."
            )
        }
        if (payload.conversations.any {
                runCatching { ChatMessage.Sender.valueOf(it.sender) }.isFailure
            }
        ) {
            throw BackupRestoreException(
                "The backup is inconsistent (a message has an unknown sender). It was not restored."
            )
        }
        if (payload.routines.any { runCatching { RoutineStatus.valueOf(it.status) }.isFailure }) {
            throw BackupRestoreException(
                "The backup is inconsistent (a routine has an unknown status). It was not restored."
            )
        }
    }

    private fun restoreCredentials(credentials: CredentialsBackup) {
        val failures = ArrayList<String>()
        for ((provider, key) in credentials.providerApiKeys) {
            val id = runCatching { ProviderCredentialId.ApiKey(provider) }.getOrNull()
            if (id == null || providerCredentialStore.write(id, key) !is CredentialStoreResult.Success) {
                failures += provider
            }
        }
        credentials.elevenLabsApiKey?.let { key ->
            if (providerCredentialStore.write(ProviderCredentialId.ElevenLabsApiKey, key)
                !is CredentialStoreResult.Success
            ) failures += "ElevenLabs"
        }
        credentials.huggingFaceToken?.let { key ->
            if (providerCredentialStore.write(ProviderCredentialId.HuggingFaceToken, key)
                !is CredentialStoreResult.Success
            ) failures += "Hugging Face"
        }
        for ((key, value) in credentials.sensitiveMemories) {
            if (!sensitiveMemoryStore.write(key, value)) failures += "a sensitive memory"
        }
        if (failures.isNotEmpty()) {
            throw BackupRestoreException(
                "Your credentials could not be restored (${failures.joinToString()}). " +
                    "Nothing else was restored."
            )
        }
    }

    /**
     * Replaces the backed-up Room tables in one transaction. Delete order and
     * insert order are both sessions-first for readability; the schema declares
     * no foreign keys, so no ordering hazard exists either way. Tables the
     * backup does not cover (task history, notifications, models, social…)
     * are left exactly as they are.
     */
    private suspend fun applyRoomSections(payload: BackupPayload) {
        database.withTransaction {
            val conversationDao = database.conversationDao()
            val sessionDao = database.chatSessionDao()
            conversationDao.clearAll()
            database.memoryDao().clearAllMemories()
            sessionDao.clearAllSessions()
            database.macroDao().clearAllMacros()
            database.planDao().clearAllPlans()
            database.habitDao().clearAllRoutines()

            payload.sessions.forEach { sessionDao.insert(it.toEntity()) }
            payload.conversations.forEach { conversationDao.insertMessage(it.toEntity()) }
            payload.memories.forEach { database.memoryDao().insertOrUpdateMemory(it.toEntity()) }
            payload.macros.forEach { database.macroDao().insertMacro(it.toEntity()) }
            payload.plans.forEach { database.planDao().insertOrUpdatePlan(it.toEntity()) }
            payload.routines.forEach { database.habitDao().insertRoutine(it.toEntity()) }

            // isCurrent invariant: exactly one current session. The backup's own
            // flags normally guarantee this; if they don't (hand-edited or partial
            // backup), mark the most recently updated session current.
            val currentCount = payload.sessions.count { it.isCurrent }
            if (payload.sessions.isNotEmpty() && currentCount != 1) {
                val newest = payload.sessions.maxByOrNull { it.updatedAt }!!
                sessionDao.markCurrent(newest.id)
            }
        }
    }

    private fun restoreAppSettings(appSettings: AppSettingsBackup) {
        appSettingsStore.setOnboardingCompleted(appSettings.onboardingCompleted)
        appSettingsStore.setHuggingFaceLastVerified(appSettings.huggingFaceLastVerified)
    }

    private fun currentDbVersion(): Int = database.openHelper.readableDatabase.version

    private companion object {
        const val SLICE_BYTES = 32 * 1024
        const val TEMP_FILE_PREFIX = "backup-restore"
        const val TEMP_FILE_SUFFIX = ".json"
    }
}

/** A backup that decrypted fine but must not be applied, or a restore that failed mid-apply. */
class BackupRestoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
