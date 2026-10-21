package com.opendroid.ai.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper over [BiometricPrompt] for the app lock (A5), shared by the
 * lock gate and the Settings flows.
 *
 * The allowed authenticators are always BIOMETRIC_STRONG | DEVICE_CREDENTIAL:
 * a device with a screen lock always has a working path, and the androidx
 * library maps the combination onto `setDeviceCredentialAllowed` behaviour on
 * API 26–29 (the app's minSdk is 26). No negative button is set — with a
 * device credential allowed, Android provides its own "use PIN/pattern"
 * affordance, and a negative button would be rejected by the builder anyway.
 */
object BiometricAuth {

    private val AUTHENTICATORS: Int =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** True when a biometric or a device credential can actually be checked right now. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the system prompt. Exactly one of [onSuccess] / [onError] runs;
     * a failed attempt (wrong finger) keeps the system prompt open by itself
     * and is not an error here.
     */
    fun showPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED -> "Authentication cancelled."
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                                "Too many attempts. Try again later or use your app PIN."
                            else -> errString.toString()
                        }
                    )
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}

/** Walks the context-wrapper chain to the hosting [FragmentActivity], if any. */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
