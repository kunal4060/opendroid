package com.opendroid.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.security.AppLockManager
import com.opendroid.ai.core.security.PinHasher
import com.opendroid.ai.ui.lock.BiometricAuth
import com.opendroid.ai.ui.lock.findFragmentActivity
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * Settings → App lock section (A5).
 *
 * Every state change is authentication-first, per the checklist:
 * enabling requires a successful biometric/device-credential authentication
 * (or, when the device has neither, setting an app PIN), and disabling
 * requires a successful authentication too. The switch is driven by the
 * manager's persisted state, so a cancelled prompt leaves it untouched.
 */
@Composable
fun AppLockSettingsSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val enabled by viewModel.appLockEnabled.collectAsState()
    val timeoutSec by viewModel.appLockTimeoutSec.collectAsState()
    val hasPin by viewModel.appLockHasPin.collectAsState()
    val scope = rememberCoroutineScope()

    var authMessage by remember { mutableStateOf<String?>(null) }
    var pinSetupPurpose by remember { mutableStateOf<PinSetupPurpose?>(null) }
    var pinEntryPurpose by remember { mutableStateOf<PinEntryPurpose?>(null) }
    var showTimeoutPicker by remember { mutableStateOf(false) }
    var showNoAuthDialog by remember { mutableStateOf(false) }

    fun promptForAuth(title: String, onSuccess: () -> Unit) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            authMessage = "Authentication is unavailable here."
            return
        }
        BiometricAuth.showPrompt(
            activity = activity,
            title = title,
            onSuccess = onSuccess,
            onError = { message -> authMessage = message }
        )
    }

    fun beginEnable() {
        authMessage = null
        if (BiometricAuth.canAuthenticate(context)) {
            promptForAuth("Enable app lock") { viewModel.setAppLockEnabled(true) }
        } else {
            // No biometric and no device credential on this device: the only
            // honest way to enable the lock is to set an app PIN first.
            pinSetupPurpose = PinSetupPurpose.ENABLE
        }
    }

    fun beginDisable() {
        authMessage = null
        when {
            BiometricAuth.canAuthenticate(context) ->
                promptForAuth("Disable app lock") { viewModel.setAppLockEnabled(false) }
            hasPin -> pinEntryPurpose = PinEntryPurpose.DISABLE
            else -> showNoAuthDialog = true
        }
    }

    fun beginSetOrChangePin() {
        authMessage = null
        when {
            // Changing an existing credential requires proving the current one.
            hasPin && BiometricAuth.canAuthenticate(context) ->
                promptForAuth("Verify it's you") { pinSetupPurpose = PinSetupPurpose.CHANGE }
            hasPin -> pinEntryPurpose = PinEntryPurpose.VERIFY_BEFORE_CHANGE
            // No PIN yet: setting the first one is itself the deliberate act;
            // when a biometric exists, still ask for it first.
            BiometricAuth.canAuthenticate(context) ->
                promptForAuth("Verify it's you") { pinSetupPurpose = PinSetupPurpose.CHANGE }
            else -> pinSetupPurpose = PinSetupPurpose.CHANGE
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "App Lock",
                    tint = AccentCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "APP LOCK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Require biometric, device credential, or app PIN " +
                            "to open OpenDroid.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { wantEnabled ->
                        if (wantEnabled) beginEnable() else beginDisable()
                    }
                )
            }

            authMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = message, fontSize = 12.sp, color = AccentRed)
            }

            AnimatedVisibility(visible = enabled) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = BorderColor)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTimeoutPicker = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Lock timeout", fontSize = 13.sp, color = TextPrimary)
                            Text(
                                text = timeoutLabel(timeoutSec),
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Choose lock timeout",
                            tint = TextSecondary
                        )
                    }
                    HorizontalDivider(color = BorderColor)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { beginSetOrChangePin() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (hasPin) "Change app PIN" else "Set app PIN",
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = if (hasPin) {
                                    "Fallback unlock with a 4–6 digit PIN."
                                } else {
                                    "Optional fallback if biometrics are unavailable."
                                },
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Set or change app PIN",
                            tint = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Forgot your app PIN and no biometric is available? " +
                            "Erase the app data from Android Settings → Apps → " +
                            "OpenDroid → Storage to start over.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }

    if (showTimeoutPicker) {
        AlertDialog(
            onDismissRequest = { showTimeoutPicker = false },
            title = { Text("Lock timeout") },
            text = {
                Column {
                    TIMEOUT_OPTIONS.forEach { (seconds, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAppLockTimeout(seconds)
                                    showTimeoutPicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = timeoutSec == seconds,
                                onClick = {
                                    viewModel.setAppLockTimeout(seconds)
                                    showTimeoutPicker = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, fontSize = 14.sp, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimeoutPicker = false }) { Text("Cancel") }
            }
        )
    }

    pinSetupPurpose?.let { purpose ->
        PinSetupDialog(
            title = if (purpose == PinSetupPurpose.ENABLE) "Set an app PIN" else "Set a new app PIN",
            onDismiss = { pinSetupPurpose = null },
            onConfirm = { newPin ->
                scope.launch {
                    if (viewModel.setAppLockPin(newPin)) {
                        if (purpose == PinSetupPurpose.ENABLE) {
                            viewModel.setAppLockEnabled(true)
                        }
                        pinSetupPurpose = null
                    } else {
                        authMessage = "The PIN could not be saved. Try again."
                        pinSetupPurpose = null
                    }
                }
            }
        )
    }

    pinEntryPurpose?.let { purpose ->
        PinEntryDialog(
            title = if (purpose == PinEntryPurpose.DISABLE) {
                "Enter app PIN to disable"
            } else {
                "Enter current app PIN"
            },
            onDismiss = { pinEntryPurpose = null },
            onVerify = { candidate -> viewModel.verifyAppLockPin(candidate) },
            onVerified = {
                pinEntryPurpose = null
                when (purpose) {
                    PinEntryPurpose.DISABLE -> viewModel.setAppLockEnabled(false)
                    PinEntryPurpose.VERIFY_BEFORE_CHANGE ->
                        pinSetupPurpose = PinSetupPurpose.CHANGE
                }
            }
        )
    }

    if (showNoAuthDialog) {
        AlertDialog(
            onDismissRequest = { showNoAuthDialog = false },
            title = { Text("Authentication unavailable") },
            text = {
                Text(
                    "This device currently has no biometric, screen lock, or app PIN " +
                        "to verify you with, so the app lock cannot be changed here."
                )
            },
            confirmButton = {
                TextButton(onClick = { showNoAuthDialog = false }) { Text("OK") }
            }
        )
    }
}

private enum class PinSetupPurpose { ENABLE, CHANGE }
private enum class PinEntryPurpose { DISABLE, VERIFY_BEFORE_CHANGE }

private val TIMEOUT_OPTIONS: List<Pair<Int, String>> = listOf(
    0 to "Immediately",
    60 to "After 1 minute",
    300 to "After 5 minutes"
)

private fun timeoutLabel(seconds: Int): String =
    TIMEOUT_OPTIONS.firstOrNull { it.first == seconds }?.second
        ?: "After $seconds seconds"

/** Two-field PIN creation: enter + confirm, 4–6 digits, must match. */
@Composable
private fun PinSetupDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PinField(
                    value = pin,
                    onValueChange = { pin = it; error = null },
                    label = "PIN (4–6 digits)"
                )
                Spacer(modifier = Modifier.height(8.dp))
                PinField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = "Confirm PIN"
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, fontSize = 12.sp, color = AccentRed)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        !PinHasher.isValidPinFormat(pin) ->
                            error = "PIN must be 4–6 digits."
                        pin != confirm -> error = "PINs do not match."
                        else -> onConfirm(pin)
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Single-field PIN check used by the disable / change-PIN flows. */
@Composable
private fun PinEntryDialog(
    title: String,
    onDismiss: () -> Unit,
    onVerify: suspend (String) -> AppLockManager.PinVerification,
    onVerified: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PinField(
                    value = pin,
                    onValueChange = { pin = it; error = null },
                    label = "App PIN",
                    enabled = !checking
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, fontSize = 12.sp, color = AccentRed)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length >= 4 && !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        when (val result = onVerify(pin)) {
                            AppLockManager.PinVerification.Success -> onVerified()
                            is AppLockManager.PinVerification.Incorrect -> {
                                checking = false
                                pin = ""
                                error = "Incorrect PIN. " +
                                    "${result.attemptsRemaining} attempt(s) left."
                            }
                            is AppLockManager.PinVerification.InCooldown -> {
                                checking = false
                                pin = ""
                                error = "Too many incorrect attempts. Try again in " +
                                    "${(result.remainingMs + 999) / 1000} seconds."
                            }
                            AppLockManager.PinVerification.NoPinSet -> {
                                checking = false
                                error = "No app PIN is set."
                            }
                        }
                    }
                }
            ) { Text("Verify") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            onValueChange(candidate.filter { it.isDigit() }.take(PinHasher.MAX_PIN_LENGTH))
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}
