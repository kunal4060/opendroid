package com.opendroid.ai.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.backup.BackupCrypto
import com.opendroid.ai.core.backup.BackupCryptoException
import com.opendroid.ai.core.backup.BackupManager
import com.opendroid.ai.core.backup.BackupRestoreException
import com.opendroid.ai.core.privacy.ForgetEverythingUseCase
import com.opendroid.ai.data.backup.BackupSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for Settings → Backup & Restore (feature B2). */
sealed interface BackupRestoreUiState {
    data object Idle : BackupRestoreUiState

    /** An export, an inspection or a restore is running; [label] says which. */
    data class Working(val label: String) : BackupRestoreUiState

    data class ExportDone(val summary: BackupSummary) : BackupRestoreUiState

    /** The chosen file decrypted and validated; nothing restored yet. */
    data class AwaitingRestoreConfirmation(val summary: BackupSummary) : BackupRestoreUiState

    data class RestoreDone(val summary: BackupSummary) : BackupRestoreUiState

    data class Error(val message: String) : BackupRestoreUiState
}

/**
 * Drives the Backup & Restore settings section. All real work lives in
 * [BackupManager]; this class owns the SAF stream plumbing (opening the
 * user-picked URI through the ContentResolver) and translates outcomes —
 * including the crypto layer's wrong-password / damaged-file failures —
 * into one state flow the section renders.
 */
@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val forgetEverythingUseCase: ForgetEverythingUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<BackupRestoreUiState>(BackupRestoreUiState.Idle)
    val state: StateFlow<BackupRestoreUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun suggestedFileName(): String = backupManager.suggestedFileName()

    /** Encrypts a backup of the live stores into the SAF destination [uri]. */
    fun exportTo(uri: Uri, passphrase: String, includeCredentials: Boolean) {
        if (!isValidPassphrase(passphrase)) return
        runWork("Creating your encrypted backup…") {
            val chars = passphrase.toCharArray()
            try {
                val summary = context.contentResolver.openOutputStream(uri)?.use { output ->
                    backupManager.exportBackup(chars, includeCredentials, output)
                } ?: throw IOException("Could not open the chosen file for writing")
                BackupRestoreUiState.ExportDone(summary)
            } finally {
                chars.fill(' ')
            }
        }
    }

    /**
     * Decrypts and validates the SAF source [uri] without touching live data.
     * Success lands in [BackupRestoreUiState.AwaitingRestoreConfirmation];
     * a wrong password lands in [BackupRestoreUiState.Error].
     */
    fun importFrom(uri: Uri, passphrase: String) {
        if (passphrase.isEmpty()) {
            _state.value = BackupRestoreUiState.Error("Enter the backup password.")
            return
        }
        runWork("Checking the backup file…") {
            val chars = passphrase.toCharArray()
            try {
                val summary = context.contentResolver.openInputStream(uri)?.use { input ->
                    backupManager.inspectBackup(chars, input)
                } ?: throw IOException("Could not open the chosen file for reading")
                BackupRestoreUiState.AwaitingRestoreConfirmation(summary)
            } finally {
                chars.fill(' ')
            }
        }
    }

    /** Applies the validated pending restore (Replace semantics — the UI confirmed first). */
    fun confirmRestore() {
        runWork("Restoring your backup…") {
            BackupRestoreUiState.RestoreDone(backupManager.applyPendingRestore())
        }
    }

    /** Abandons a validated-but-unconfirmed restore; the temp file is deleted. */
    fun cancelRestore() {
        backupManager.discardPending()
        _state.value = BackupRestoreUiState.Idle
    }

    fun reset() {
        if (_state.value is BackupRestoreUiState.AwaitingRestoreConfirmation) {
            backupManager.discardPending()
        }
        _state.value = BackupRestoreUiState.Idle
    }

    /**
     * Restarts the process so every already-running flow re-reads the restored
     * stores. Reuses the A6 wipe's relaunch (schedule launcher intent, then kill
     * the process) — after a restore the fresh process is indistinguishable
     * from a fresh start on the restored data.
     */
    fun restartApp() {
        forgetEverythingUseCase.relaunchApp()
    }

    override fun onCleared() {
        backupManager.discardPending()
        super.onCleared()
    }

    private fun isValidPassphrase(passphrase: String): Boolean {
        if (passphrase.length < BackupCrypto.MIN_PASSPHRASE_LENGTH) {
            _state.value = BackupRestoreUiState.Error(
                "Backup password must be at least ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters."
            )
            return false
        }
        return true
    }

    private fun runWork(label: String, block: suspend () -> BackupRestoreUiState) {
        job?.cancel()
        _state.value = BackupRestoreUiState.Working(label)
        job = viewModelScope.launch {
            _state.value = try {
                block()
            } catch (failure: BackupCryptoException) {
                BackupRestoreUiState.Error(failure.message ?: "The backup file could not be used.")
            } catch (failure: BackupRestoreException) {
                BackupRestoreUiState.Error(failure.message ?: "The restore failed.")
            } catch (failure: IOException) {
                BackupRestoreUiState.Error(
                    "The file could not be read or written: ${failure.localizedMessage ?: "unknown error"}"
                )
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                BackupRestoreUiState.Error(failure.localizedMessage ?: "Something went wrong.")
            }
        }
    }
}
