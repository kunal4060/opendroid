package com.opendroid.ai.core.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.opendroid.ai.MainActivity

/**
 * C3 (Tier 1) — handles `Intent.ACTION_ASSIST`.
 *
 * Per the platform role contract, an app qualifies for ROLE_ASSISTANT by
 * handling ACTION_ASSIST even without the full VoiceInteractionService
 * stack (which is Tier 2, also implemented). This activity is the
 * guaranteed invocation path on devices/OEM builds where the VIS session
 * is not offered: it is translucent and never draws — it immediately
 * bridges into the real chat in [MainActivity], carrying any query extra
 * the invocation included (honoured by MainActivity →
 * [PendingAssistantCommand] → ChatViewModel's normal send path).
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val parsed = AssistantIntentParser.parse(intent?.action, AssistantIntentParser.extrasOf(intent))

        val target = Intent(this, MainActivity::class.java).apply {
            // MainActivity is singleTask; these flags make a warm instance
            // receive onNewIntent instead of stacking a second copy.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Intent.ACTION_MAIN
            putExtra(AssistantIntentParser.EXTRA_FROM_ASSISTANT, true)
            parsed.query?.let { putExtra(AssistantIntentParser.EXTRA_ASSIST_QUERY, it) }
        }
        startActivity(target)
        finish()
    }
}
