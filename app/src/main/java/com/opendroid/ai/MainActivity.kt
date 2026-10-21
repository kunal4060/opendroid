package com.opendroid.ai

import android.Manifest
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
import com.opendroid.ai.core.security.AppLockManager
import com.opendroid.ai.core.service.OpenDroidService
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.ui.OpenDroidNavigation
import com.opendroid.ai.ui.lock.LockGate
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableOpenDroidEdgeToEdge(isDarkTheme = true)
        super.onCreate(savedInstanceState)

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

            OpenDroidTheme(isDarkTheme = config.isDarkMode) {
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

    override fun onDestroy() {
        super.onDestroy()
        // We want the foreground service to continue running even if UI is destroyed
        // to maintain wake word tracking, but we can stop it if the user wants full quit.
        // For production autonomous helper, we keep the service running in background.
    }
}

