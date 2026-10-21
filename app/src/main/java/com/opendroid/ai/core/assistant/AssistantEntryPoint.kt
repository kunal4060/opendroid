package com.opendroid.ai.core.assistant

import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.data.repository.ConversationRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * C3 — Hilt access for the voice-interaction stack.
 *
 * VoiceInteractionService / VoiceInteractionSession objects are created by
 * the platform, not by Hilt, so they cannot use constructor or field
 * injection. Following the existing pattern used by SocialScheduleWorker
 * and ModelDownloadWorker, they pull the singletons they need through this
 * entry point via EntryPointAccessors.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AssistantEntryPoint {
    fun agentLoop(): AgentLoop
    fun conversationRepository(): ConversationRepository
}
