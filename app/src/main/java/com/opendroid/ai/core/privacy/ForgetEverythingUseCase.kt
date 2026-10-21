package com.opendroid.ai.core.privacy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Process
import androidx.work.Operation
import androidx.work.WorkManager
import com.opendroid.ai.core.security.AndroidProviderCredentialStore
import com.opendroid.ai.core.security.AndroidSensitiveMemoryStore
import com.opendroid.ai.core.security.AndroidSocialCredentialStore
import com.opendroid.ai.core.security.AndroidUserProfileStore
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProfileStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.core.security.SensitiveMemoryStore
import com.opendroid.ai.core.security.SocialCredentialStore
import com.opendroid.ai.core.external.ExternalAutomationStore
import com.opendroid.ai.core.google.GoogleAccountStore
import com.opendroid.ai.core.learning.LearnedActionStore
import com.opendroid.ai.core.rag.RagIndexStore
import com.opendroid.ai.core.schedule.ScheduledTaskScheduler
import com.opendroid.ai.core.schedule.ScheduledTaskStore
import com.opendroid.ai.core.security.AndroidAppLockStore
import com.opendroid.ai.core.security.AppLockStore
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.core.research.AndroidResearchSettingsStore
import com.opendroid.ai.core.service.McpConfigStore
import com.opendroid.ai.core.service.OpenDroidService
import com.opendroid.ai.core.settings.AndroidAppSettingsStore
import com.opendroid.ai.core.usage.UsageRepository
import com.opendroid.ai.core.widget.WidgetEventRecorder
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.repository.PersonaRepository
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-tap "forget everything" wipe (A6).
 *
 * Orchestrates the app's existing deletion primitives in a safe order — quiesce background
 * work first, then the database, settings and every credential store, then the preference
 * files and Keystore aliases underneath them, then user files — and reports per-area results
 * via [WipeOrchestrator]. Nothing here invents a new storage mechanism; each step is a store
 * the app already owns, cleared through its own API or, for the file/Keystore layers, through
 * the platform API for the exact names those stores use.
 *
 * The wipe only counts as complete when every step reports success. The caller is expected to
 * show the [WipeReport] and then call [relaunchApp], because some state (in-memory caches,
 * SharedPreferences instances held by singletons) can only be fully dropped by a fresh
 * process — and a fresh process with the onboarding flag erased routes to onboarding, exactly
 * like a fresh install.
 */
@Singleton
class ForgetEverythingUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: OpenDroidDatabase,
    private val settingsRepository: SettingsRepository,
    private val personaRepository: PersonaRepository,
    private val providerCredentialStore: ProviderCredentialStore,
    private val userProfileStore: UserProfileStore,
    private val sensitiveMemoryStore: SensitiveMemoryStore,
    private val socialCredentialStore: SocialCredentialStore,
    private val learnedActionStore: LearnedActionStore,
    private val researchSettingsStore: com.opendroid.ai.core.research.ResearchSettingsStore,
    private val ragIndexStore: RagIndexStore,
    private val appLockStore: AppLockStore,
    private val scheduledTaskStore: ScheduledTaskStore,
    private val scheduledTaskScheduler: ScheduledTaskScheduler,
    private val usageRepository: UsageRepository,
    private val externalAutomationStore: ExternalAutomationStore,
    private val googleAccountStore: GoogleAccountStore
) {

    /** Runs the full wipe off the main thread and streams each finished step to [onStepFinished]. */
    suspend fun execute(
        onStepFinished: (WipeStepResult) -> Unit = {}
    ): WipeReport = withContext(Dispatchers.IO) {
        WipeOrchestrator(buildTargets()).execute(onStepFinished)
    }

    /**
     * Schedules a relaunch of the app's launcher activity, then kills this process. The new
     * process starts from erased stores: startup routing sees no onboarding flag and lands on
     * onboarding, indistinguishable from a fresh install.
     */
    fun relaunchApp() {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launchIntent != null) {
            val pendingIntent = PendingIntent.getActivity(
                context,
                RELAUNCH_REQUEST_CODE,
                launchIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.RTC,
                System.currentTimeMillis() + RELAUNCH_DELAY_MS,
                pendingIntent
            )
        }
        Process.killProcess(Process.myPid())
    }

    private fun buildTargets(): List<WipeTarget> = listOf(
        WipeTarget(
            id = "background-work",
            title = "Background agent & scheduled work",
            remainingDescription = "Background work may still run or be scheduled on this device.",
            clear = { quiesceBackgroundWork() }
        ),
        WipeTarget(
            id = "scheduled-tasks",
            title = "Scheduled tasks",
            remainingDescription = "Your scheduled tasks are still stored on this device and may still run.",
            clear = { clearScheduledTasks() }
        ),
        WipeTarget(
            id = "database",
            title = "Chats, memories, plans & history",
            remainingDescription =
                "Your chats, memories & knowledge graph, plans, macros and history are still in the app database.",
            clear = { database.clearAllTables() }
        ),
        WipeTarget(
            id = "settings",
            title = "App settings",
            remainingDescription = "Your app settings are still stored on this device.",
            clear = { settingsRepository.clearAllSettings() }
        ),
        WipeTarget(
            id = "action-learning",
            title = "Action Learning teachings",
            remainingDescription = "Your taught meanings for invented actions are still stored on this device.",
            clear = { learnedActionStore.clearAll() }
        ),
        WipeTarget(
            id = "persona-library",
            title = "Personas & quick commands",
            remainingDescription = "Your saved personas and quick commands are still stored on this device.",
            clear = { personaRepository.clearAllPersonas() }
        ),
        WipeTarget(
            id = "knowledge-base",
            title = "Document knowledge base",
            remainingDescription = "Your imported documents' indexed text is still stored on this device.",
            clear = { ragIndexStore.clearAll() }
        ),
        WipeTarget(
            id = "usage-history",
            title = "Usage history & model prices",
            remainingDescription = "Your token/cost usage history and custom model prices are still stored on this device.",
            clear = { usageRepository.clearAll() }
        ),
        WipeTarget(
            id = "sensitive-memory",
            title = "Sensitive memories",
            remainingDescription = "Your sensitive (Level 4) memories are still stored on this device.",
            clear = {
                check(sensitiveMemoryStore.clearAll()) { "Sensitive memory store did not clear" }
            }
        ),
        WipeTarget(
            id = "app-lock",
            title = "App lock & PIN",
            remainingDescription = "Your app lock (including its PIN) is still set on this device.",
            clear = {
                check(appLockStore.clearAll()) { "App lock store did not clear" }
            }
        ),
        WipeTarget(
            id = "provider-credentials",
            title = "AI provider API keys",
            remainingDescription = "Your AI provider API keys are still stored on this device.",
            clear = { clearProviderCredentials() }
        ),
        WipeTarget(
            id = "research-settings",
            title = "Web research API keys",
            remainingDescription = "Your web research settings (search API keys, SearXNG instance) are still stored on this device.",
            clear = {
                check(researchSettingsStore.clearAll()) { "Research settings store did not clear" }
            }
        ),
        WipeTarget(
            id = "social-credentials",
            title = "Social account tokens",
            remainingDescription = "Your social account tokens are still stored on this device.",
            clear = {
                check(socialCredentialStore.clearAll()) { "Social credential store did not clear" }
            }
        ),
        WipeTarget(
            id = "external-automation",
            title = "External automation token",
            remainingDescription = "External apps can still control OpenDroid with your automation token.",
            // The token record dies here; the enable flag and last-run record in
            // the same file die with the whole file in the preference-files step.
            clear = { externalAutomationStore.clearToken() }
        ),
        WipeTarget(
            id = "google-account",
            title = "Connected Google account",
            remainingDescription = "Your Google account connection (Gmail & Calendar) is still stored on this device.",
            clear = {
                check(googleAccountStore.clearAll()) { "Google account store did not clear" }
            }
        ),
        WipeTarget(
            id = "user-profile",
            title = "Your profile",
            remainingDescription = "Your name and date of birth are still stored on this device.",
            clear = {
                val result = userProfileStore.resetForReentry()
                check(result is ProfileStoreResult.Success) { "Profile store did not clear" }
            }
        ),
        WipeTarget(
            id = "widget-meta",
            title = "Widget data",
            remainingDescription = "Some home-screen widget data is still stored on this device.",
            clear = { WidgetEventRecorder.clearAll(context) }
        ),
        WipeTarget(
            id = "preference-files",
            title = "Stored preference files",
            remainingDescription = "Some app preference files are still on this device.",
            clear = { deletePreferenceFiles() }
        ),
        WipeTarget(
            id = "encryption-keys",
            title = "Encryption keys",
            remainingDescription = "Some encryption keys are still in the Android Keystore.",
            clear = { deleteKeystoreAliases() }
        ),
        WipeTarget(
            id = "files",
            title = "Downloaded models & workspace files",
            remainingDescription = "Some downloaded model or workspace files are still on this device.",
            clear = { deleteUserFiles() }
        )
    )

    /** Stops the foreground service and cancels every scheduled background job. */
    private fun quiesceBackgroundWork() {
        OpenDroidService.stop(context)
        // WorkManager auto-initialises at app start; if it somehow never did, there is no
        // scheduled work to cancel and this step is already satisfied.
        val workManager = try {
            WorkManager.getInstance(context)
        } catch (notInitialised: IllegalStateException) {
            return
        }
        val cancelState = workManager.cancelAllWork().result.get(CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        check(cancelState is Operation.State.SUCCESS) { "Cancelling background work did not complete" }
        workManager.pruneWork()
    }

    /**
     * Disarms every scheduled task's alarm, then erases the schedule store.
     * The order matters: clearing the store alone would leave already-registered
     * AlarmManager alarms pointing at tasks that no longer exist, and the
     * WorkManager cancellation in the background-work step cannot reach them.
     * With the store empty, the boot/sweep re-arm paths find nothing to resurrect.
     */
    private suspend fun clearScheduledTasks() {
        scheduledTaskStore.snapshot().forEach { task -> scheduledTaskScheduler.cancel(task.id) }
        scheduledTaskStore.clearAll()
    }

    /**
     * Removes every stored provider credential through the store's own API (which writes an
     * authenticated tombstone per credential, so a later legacy import cannot resurrect it).
     * The enclosing preference file and Keystore alias are deleted by their own steps below,
     * which removes the tombstones themselves.
     */
    private fun clearProviderCredentials() {
        val apiKeys = when (val result = providerCredentialStore.readProviderApiKeys()) {
            is CredentialStoreResult.Success -> result.value
            else -> throw IllegalStateException("Could not read the stored provider API keys")
        }
        val credentials = ArrayList<Pair<String, ProviderCredentialId>>()
        for (providerName in apiKeys.keys) {
            credentials += providerName to ProviderCredentialId.ApiKey(providerName)
        }
        credentials += "ElevenLabs" to ProviderCredentialId.ElevenLabsApiKey
        credentials += "Hugging Face" to ProviderCredentialId.HuggingFaceToken

        val failures = ArrayList<String>()
        for ((label, credential) in credentials) {
            if (providerCredentialStore.remove(credential) !is CredentialStoreResult.Success) {
                failures += label
            }
        }
        check(failures.isEmpty()) { "Could not remove credentials for: ${failures.joinToString()}" }
    }

    /**
     * Deletes every SharedPreferences file the app writes, by name. Only files that actually
     * exist are checked against the platform result; a file that was never created is already
     * erased. Deleting the files (not just their keys) is what makes the onboarding flag, the
     * credential envelopes and the legacy plaintext store genuinely gone for the next process.
     */
    private fun deletePreferenceFiles() {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val failures = ArrayList<String>()
        for (name in PREFERENCE_FILES) {
            if (!File(sharedPrefsDir, "$name.xml").exists()) continue
            if (!context.deleteSharedPreferences(name)) failures += name
        }
        check(failures.isEmpty()) { "Could not delete preference files: ${failures.joinToString()}" }
    }

    /**
     * Deletes the app's Android Keystore aliases. Any ciphertext that somehow survived the
     * earlier steps is permanently undecryptable once its alias is gone. Aliases already
     * dropped by a store's own reset (profile, social) are skipped.
     */
    private fun deleteKeystoreAliases() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val failures = ArrayList<String>()
        for (alias in KEY_ALIASES) {
            try {
                if (keyStore.containsAlias(alias)) {
                    keyStore.deleteEntry(alias)
                }
                if (keyStore.containsAlias(alias)) failures += alias
            } catch (failure: Exception) {
                failures += alias
            }
        }
        check(failures.isEmpty()) { "Could not delete encryption keys: ${failures.joinToString()}" }
    }

    /**
     * Deletes the contents (not the directories themselves) of every app-private location
     * holding user content: downloaded on-device models and their local-import copies, the
     * default workspace, saved pictures, the local copies of chat attachments and of images
     * shared into the app, and both caches (chat exports land in the cache).
     * User-picked SAF folders outside app storage are the user's own files and are left alone;
     * only the stored pointer to them (in the erased preference files) is forgotten.
     */
    private fun deleteUserFiles() {
        val externalBase = context.getExternalFilesDir(null) ?: context.filesDir
        val roots = listOfNotNull(
            File(externalBase, "models"),
            File(context.filesDir, "litert_models"),
            File(context.filesDir, "attachments"), // AttachmentProcessor local copies (B1)
            File(context.filesDir, "shared_inbox"), // SharedImageStore inbox (C2)
            File(externalBase, "workspace"),
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            context.cacheDir,
            context.externalCacheDir
        )
        val failures = ArrayList<String>()
        for (root in roots) {
            if (!root.exists()) continue
            val children = root.listFiles() ?: continue
            for (child in children) {
                child.deleteRecursively()
                if (child.exists()) failures += child.name
            }
        }
        check(failures.isEmpty()) { "Could not delete files: ${failures.joinToString()}" }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val RELAUNCH_REQUEST_CODE = 0xA6
        const val RELAUNCH_DELAY_MS = 300L
        const val CANCEL_TIMEOUT_SECONDS = 60L

        /**
         * Every SharedPreferences file the app writes, in one inventory. The first ten are the
         * stores' own published names; the rest are named here because their owners keep the
         * constant private — each comment names the owner so this list can be audited against
         * a `getSharedPreferences` search.
         */
        val PREFERENCE_FILES: List<String> = listOf(
            AndroidProviderCredentialStore.PREFERENCES_NAME,
            AndroidUserProfileStore.PREFERENCES_NAME,
            AndroidSensitiveMemoryStore.PREFERENCES_NAME,
            AndroidSocialCredentialStore.PREFERENCES_NAME,
            McpConfigStore.PREFERENCES,
            AndroidAppSettingsStore.PREFERENCES_NAME,
            AndroidAppLockStore.PREFERENCES_NAME,
            AndroidResearchSettingsStore.PREFERENCES_NAME,
            ExternalAutomationStore.PREFERENCES_NAME,
            GoogleAccountStore.PREFERENCES_NAME,
            "opendroid_prefs", // LegacyPlaintextPreferencesSource (legacy plaintext store)
            "permission_asked", // PermissionAskedStore
            "opendroid_storage_prefs", // StorageWorkspaceProvider
            "opendroid_social_settings" // SocialManager
        )

        /** Every Android Keystore alias the app's secret stores create. */
        val KEY_ALIASES: List<String> = listOf(
            AndroidProviderCredentialStore.KEY_ALIAS,
            AndroidUserProfileStore.KEY_ALIAS,
            AndroidSensitiveMemoryStore.KEY_ALIAS,
            AndroidSocialCredentialStore.KEY_ALIAS,
            McpConfigStore.KEY_ALIAS,
            AndroidAppLockStore.KEY_ALIAS,
            AndroidResearchSettingsStore.KEY_ALIAS,
            GoogleAccountStore.KEY_ALIAS
        )
    }
}
