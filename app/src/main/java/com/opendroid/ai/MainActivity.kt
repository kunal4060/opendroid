package com.opendroid.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.opendroid.ai.core.intent.ExternalIntentRouter
import com.opendroid.ai.core.intent.ExternalLaunch
import com.opendroid.ai.core.launch.ExternalIntents
import com.opendroid.ai.core.security.AppLockManager
import com.opendroid.ai.core.service.OpenDroidService
import com.opendroid.ai.core.share.SharedIntentParser
import com.opendroid.ai.core.share.SharingShortcutPublisher
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.ui.OpenDroidNavigation
import com.opendroid.ai.ui.lock.LockGate
import com.opendroid.ai.ui.theme.AccentTheme
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.OpenDroidTheme
import com.opendroid.ai.ui.theme.enableOpenDroidEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// FragmentActivity (a ComponentActivity subclass) so BiometricPrompt can be
// hosted for the app lock (A5); setContent, Hilt, and the edge-to-edge theme
// helper — which is typed on ComponentActivity — are unaffected.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var appLockManager: AppLockManager

    @Inject lateinit var externalIntentRouter: ExternalIntentRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableOpenDroidEdgeToEdge(isDarkTheme = true)
        super.onCreate(savedInstanceState)

        // Cold start: hand any external intent — an inbound share (C2) or a
        // widget/shortcut launch (C1); C3/C7 ride the same path — to the one
        // intent router. The router replays it to the UI once the lock gate
        // and the splash/onboarding flow let the UI compose — nothing is
        // rendered or staged before then.
        routeLaunchIntent(intent)

        // Direct Share row (C2): publish the long-lived sharing shortcut so
        // OpenDroid can surface as a direct target in other apps' share
        // sheets. Best-effort; the manifest share filter works without it.
        SharingShortcutPublisher.publishOnce(this)

        // Start foreground assistant service only if RECORD_AUDIO permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            OpenDroidService.start(this)
        }

        setContent {
            val config by settingsRepository.llmConfig.collectAsState(
                initial = com.opendroid.ai.data.models.LLMConfig()
            )
            val locked by appLockManager.isLocked.collectAsState()

            // While locked, keep the recents thumbnail and screenshots blank.
            // The lock state is known before the first frame composes (cold
            // start derives it from the persisted setting), so FLAG_SECURE is
            // already set when the gate first draws.
            DisposableEffect(locked) {
                if (locked) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose { }
            }

            OpenDroidTheme(
                isDarkTheme = config.isDarkMode,
                accentTheme = AccentTheme.fromId(config.accentTheme),
                useDynamicColor = config.useDynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppTheme.colors.background
                ) {
                    if (locked) {
                        // The NavHost is never composed while locked, so no
                        // chat / settings / memory content exists underneath
                        // the gate (A5).
                        LockGate(appLockManager = appLockManager)
                    } else {
                        OpenDroidNavigation()
                    }
                }
            }
        }
    }

    /**
     * Warm start: with launchMode=singleTask the running instance receives
     * new external intents here — widget/shortcut taps and shares while the
     * app is running — instead of a second activity being created.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeLaunchIntent(intent)
    }

    /**
     * Routes one external intent to the [ExternalIntentRouter]: an inbound
     * share (C2, ACTION_SEND / SEND_MULTIPLE) is parsed by
     * [SharedIntentParser]; a widget/shortcut launch (C1) is parsed from
     * the [ExternalIntents] wire format. Anything else (the plain launcher
     * intent) routes nowhere.
     *
     * The intent is marked once routed: the same intent object is
     * redelivered when the activity is recreated (rotation, theme change),
     * and a recreation must not publish the same launch a second time.
     * (The router's claim is the second line of defence on the consumer
     * side.)
     */
    private fun routeLaunchIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_LAUNCH_ROUTED, false)) return
        if (SharedIntentParser.isShareIntent(intent)) {
            intent.putExtra(EXTRA_LAUNCH_ROUTED, true)
            val parsed = SharedIntentParser.parse(intent) ?: return
            externalIntentRouter.publish(ExternalLaunch.SharedContent(parsed))
            return
        }
        val launch = ExternalIntents.fromIntent(intent) ?: return
        intent.putExtra(EXTRA_LAUNCH_ROUTED, true)
        externalIntentRouter.publish(launch)
    }

    override fun onDestroy() {
        super.onDestroy()
        // We want the foreground service to continue running even if UI is destroyed
        // to maintain wake word tracking, but we can stop it if the user wants full quit.
        // For production autonomous helper, we keep the service running in background.
    }

    companion object {
        private const val EXTRA_LAUNCH_ROUTED = "com.opendroid.ai.extra.LAUNCH_ROUTED"
    }
}

