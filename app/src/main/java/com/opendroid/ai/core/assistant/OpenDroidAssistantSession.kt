package com.opendroid.ai.core.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.opendroid.ai.ui.assistant.AssistantSessionContent
import com.opendroid.ai.ui.theme.OpenDroidTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * C3 (Tier 2) — one assistant invocation = one session.
 *
 * Hosts the real chat-backed assistant UI (Compose, in the session's own
 * window) and wires it to [AssistantSessionController], which dispatches
 * spoken/typed commands into the same AgentLoop the in-app chat uses.
 * Dependencies come through [AssistantEntryPoint] because the platform —
 * not Hilt — instantiates this class.
 *
 * Lifecycle: the session window has no Activity, so a
 * [SessionWindowLifecycleOwner] is attached to the ComposeView and driven
 * from the session callbacks (create → show → hide → destroy).
 */
class OpenDroidAssistantSession(context: Context) : VoiceInteractionSession(context) {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var lifecycleOwner: SessionWindowLifecycleOwner? = null
    private var controller: AssistantSessionController? = null

    override fun onCreateContentView(): View {
        val appContext = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            AssistantEntryPoint::class.java
        )
        val sessionController = AssistantSessionController(
            context = appContext,
            agentLoop = entryPoint.agentLoop(),
            conversationRepository = entryPoint.conversationRepository(),
            scope = sessionScope
        )
        controller = sessionController

        val owner = SessionWindowLifecycleOwner()
        owner.onCreate()
        lifecycleOwner = owner

        return ComposeView(context).apply {
            // ViewTree*Owner are no longer types in the KMP lifecycle /
            // savedstate artifacts: the Kotlin API is the View extension
            // setters below (the bytecode class only carries those
            // top-level functions, so importing it as a type is unresolved).
            this.setViewTreeLifecycleOwner(owner)
            this.setViewTreeViewModelStoreOwner(owner)
            this.setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                OpenDroidTheme(isDarkTheme = true) {
                    AssistantSessionContent(
                        controller = sessionController,
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleOwner?.onStart()
        // Start listening as soon as the session is visible — the whole
        // point of the gesture is speaking immediately. If the mic
        // permission is missing, the engine reports an honest error into
        // the session UI and the typed fallback stays available.
        controller?.startListening()
    }

    override fun onHandleAssist(
        args: Bundle?,
        assistStructure: AssistStructure?,
        assistContent: AssistContent?
    ) {
        super.onHandleAssist(args, assistStructure, assistContent)
        // Which app was in front when the assistant was summoned. Kept as
        // session context (shown in the UI); acting on that app's screen
        // is future work layered on this foundation.
        controller?.onAssistContext(assistStructure?.activityComponent?.packageName)
    }

    override fun onHide() {
        controller?.stopListening()
        lifecycleOwner?.onStop()
        super.onHide()
    }

    override fun onDestroy() {
        controller?.close()
        lifecycleOwner?.onDestroy()
        sessionScope.cancel()
        super.onDestroy()
    }
}
