package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.research.ResearchOutcome
import com.opendroid.ai.core.research.ResearchPipeline
import com.opendroid.ai.data.db.dao.ChatSessionDao
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature D3's planner-facing half: the `WEB_RESEARCH` action.
 *
 * Where the legacy `WEB_SEARCH` just opens a browser tab (and its result
 * text claims findings it never made), `WEB_RESEARCH` runs the real
 * [ResearchPipeline] — live search, page fetch, a grounded answer whose
 * `[n]` citations were validated against the sources stored for this
 * run — and returns that answer as the step result. AgentLoop's summary
 * copies step results verbatim, so the cited answer reaches the chat
 * intact, and the chat layer links it back to the stored sources through
 * the run record (see ResearchSourceResolver).
 *
 * Failure honesty is part of the contract: not-configured, zero-result
 * and provider failures come back as step failures carrying the exact
 * user-facing message — never as a successful step with invented content.
 */
@Singleton
class ResearchActions @Inject constructor(
    private val pipeline: ResearchPipeline,
    private val chatSessionDao: ChatSessionDao
) {

    fun getActions(): List<Action> = listOf(
        WebResearchAction(pipeline, chatSessionDao)
    )

    private class WebResearchAction(
        private val pipeline: ResearchPipeline,
        private val chatSessionDao: ChatSessionDao
    ) : Action {
        override val name: String = "WEB_RESEARCH"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"]?.takeIf { it.isNotBlank() }
                ?: return ActionResult(false, null, "query parameter is missing")
            val runId = UUID.randomUUID().toString()
            // The action path stores sources under the run id: the summary
            // message that will carry this answer doesn't exist yet. The
            // session is the chat marked current — the one this plan is
            // running in (plans execute against the current session).
            val sessionId = try {
                chatSessionDao.getCurrentSessionOnce()?.id ?: ""
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve current session for research run: ${e.message}")
                ""
            }
            return when (val outcome = pipeline.run(
                query = query,
                sessionId = sessionId,
                messageId = runId,
                runId = runId
            )) {
                is ResearchOutcome.Answer -> ActionResult.Success(
                    dataMap = mapOf(
                        "message" to outcome.text,
                        "researchRunId" to runId,
                        "sourceCount" to outcome.sources.size.toString()
                    )
                )

                is ResearchOutcome.NotConfigured ->
                    ActionResult(false, null, outcome.message)

                is ResearchOutcome.NoSources ->
                    ActionResult(false, null, outcome.message)

                is ResearchOutcome.Failed ->
                    ActionResult(false, null, outcome.message)
            }
        }
    }

    companion object {
        private const val TAG = "ResearchActions"
    }
}
