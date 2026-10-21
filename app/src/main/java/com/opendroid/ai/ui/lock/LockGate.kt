package com.opendroid.ai.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.security.AppLockManager
import com.opendroid.ai.ui.theme.AccentGreenButton
import com.opendroid.ai.ui.theme.AccentNeonGreen
import com.opendroid.ai.ui.theme.OnAccent
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lock screen (A5). Composed INSTEAD of the NavHost while the app is
 * locked, so no chat, settings, or memory content exists underneath it — not
 * for the user, not for a screenshot, not for the recents thumbnail
 * (MainActivity additionally sets FLAG_SECURE while locked).
 *
 * Unlock paths mirror what the user proved when enabling the lock:
 * biometric/device-credential via the system prompt, and/or the app PIN.
 * If neither exists any more (e.g. the device credential was removed in
 * system settings and no PIN was ever set), the gate says so plainly and
 * points at the erase-data recovery instead of offering a bypass.
 */
@Composable
fun LockGate(appLockManager: AppLockManager) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val hasPin by appLockManager.hasPin.collectAsState()
    val biometricAvailable = remember(context) { BiometricAuth.canAuthenticate(context) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var cooldownRemainingMs by remember { mutableStateOf(appLockManager.pinCooldownRemainingMs()) }
    val scope = rememberCoroutineScope()

    fun showBiometricPrompt() {
        val host = activity ?: run {
            errorMessage = "Biometric unlock is unavailable here."
            return
        }
        BiometricAuth.showPrompt(
            activity = host,
            title = "OpenDroid is locked",
            subtitle = "Authenticate to continue",
            onSuccess = { appLockManager.unlock() },
            onError = { message -> errorMessage = message }
        )
    }

    // Offer the system prompt immediately on the first composition; after a
    // cancel the user re-opens it with the Unlock button.
    LaunchedEffect(Unit) {
        if (biometricAvailable) showBiometricPrompt()
    }

    // Keep the cooldown countdown fresh while entry is blocked: each update
    // re-arms the effect, so it ticks only during a cooldown and stops at 0.
    LaunchedEffect(cooldownRemainingMs) {
        if (cooldownRemainingMs > 0L) {
            delay(500)
            cooldownRemainingMs = appLockManager.pinCooldownRemainingMs()
        }
    }

    // No bypass: back does nothing at the gate (and the content behind it is
    // not composed anyway).
    BackHandler(enabled = true) { }

    val inCooldown = cooldownRemainingMs > 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Locked",
            tint = AccentNeonGreen,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "OPENDROID IS LOCKED",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Authenticate to continue.",
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = message,
                fontSize = 13.sp,
                color = AccentRed,
                textAlign = TextAlign.Center
            )
        }

        if (biometricAvailable) {
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = { showBiometricPrompt() },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreenButton),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Unlock", color = OnAccent)
            }
        }

        if (hasPin) {
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { candidate ->
                    pin = candidate.filter { it.isDigit() }.take(6)
                    errorMessage = null
                },
                label = { Text("App PIN") },
                singleLine = true,
                enabled = !inCooldown && !verifying,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    verifying = true
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            appLockManager.verifyPin(pin)
                        }
                        verifying = false
                        when (result) {
                            AppLockManager.PinVerification.Success -> {
                                pin = ""
                                appLockManager.unlock()
                            }
                            is AppLockManager.PinVerification.Incorrect -> {
                                pin = ""
                                errorMessage =
                                    "Incorrect PIN. ${result.attemptsRemaining} attempt(s) left."
                            }
                            is AppLockManager.PinVerification.InCooldown -> {
                                pin = ""
                                cooldownRemainingMs = result.remainingMs
                                errorMessage = "Too many incorrect attempts."
                            }
                            AppLockManager.PinVerification.NoPinSet -> {
                                errorMessage = "No app PIN is set."
                            }
                        }
                    }
                },
                enabled = pin.length >= 4 && !inCooldown && !verifying,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Unlock with PIN")
            }
            if (inCooldown) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "PIN entry is paused. Try again in " +
                        "${(cooldownRemainingMs + 999) / 1000} seconds.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (!biometricAvailable && !hasPin) {
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "No unlock method is available: this device has no biometric " +
                    "or screen lock, and no app PIN was set. To regain access, erase " +
                    "the app data from Android Settings → Apps → OpenDroid → Storage.",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
