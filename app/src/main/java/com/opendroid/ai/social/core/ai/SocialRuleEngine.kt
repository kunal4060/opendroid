package com.opendroid.ai.social.core.ai

import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.domain.model.AutomationLevel
import com.opendroid.ai.social.domain.model.Sentiment
import com.opendroid.ai.social.domain.model.SocialAutomationRule
import com.opendroid.ai.social.domain.model.SocialComment
import javax.inject.Inject
import javax.inject.Singleton

sealed class RuleEvaluationResult {
    data class AutoReply(val rule: SocialAutomationRule, val replyText: String, val confidence: Float) : RuleEvaluationResult()
    data class NeedsHumanApproval(val rule: SocialAutomationRule?, val suggestedReply: String, val reason: String) : RuleEvaluationResult()
    data object NoMatch : RuleEvaluationResult()
}

@Singleton
class SocialRuleEngine @Inject constructor(
    private val socialRepository: SocialRepository,
    private val replyEngine: SocialCommentReplyEngine
) {

    private val sensitiveKeywords = setOf(
        "lawsuit", "legal", "lawyer", "sue", "refund", "scam", "fraud",
        "angry", "furious", "terrible", "stole", "password", "exploit",
        "vulnerability", "hack", "leak", "security issue", "breach"
    )

    suspend fun evaluateComment(
        comment: SocialComment,
        automationLevel: AutomationLevel,
        postContext: String? = null
    ): RuleEvaluationResult {
        // 1. Strict Security Guardrail: Check for sensitive/controversial/legal/financial keywords
        val lowerContent = comment.content.lowercase()
        val isSensitive = sensitiveKeywords.any { lowerContent.contains(it) } || comment.sentiment == Sentiment.NEGATIVE

        if (isSensitive) {
            val suggested = replyEngine.generateReply(comment, postContext)
            return RuleEvaluationResult.NeedsHumanApproval(
                rule = null,
                suggestedReply = suggested.replyText,
                reason = "Sensitive, negative, or security-related conversation. Human approval strictly required."
            )
        }

        // 2. Fetch Active Rules from Database
        val activeRules = socialRepository.getActiveRules().filter { rule ->
            rule.platform == null || rule.platform == comment.platform
        }

        for (rule in activeRules) {
            val matches = when (rule.triggerType) {
                "KEYWORD_MATCH" -> rule.keywords.any { lowerContent.contains(it.lowercase().trim()) }
                "INTENT_QUESTION" -> lowerContent.contains("?") || lowerContent.contains("how") || lowerContent.contains("what")
                "SENTIMENT_POSITIVE" -> comment.sentiment == Sentiment.POSITIVE
                else -> false
            }

            if (matches) {
                val replyText = if (!rule.replyTemplate.isNullOrBlank()) {
                    rule.replyTemplate.replace("{author}", comment.authorName)
                } else {
                    replyEngine.generateReply(comment, postContext).replyText
                }

                // The rule's actionType decides what "fire" means. Only
                // AUTO_REPLY may ever send; every other action the Add
                // Rule dialog offers resolves to a draft for a human,
                // no matter the automation level or confidence — a
                // draft-only rule must never auto-reply.
                return when (rule.actionType) {
                    "DRAFT_REPLY_NOTIFY" -> RuleEvaluationResult.NeedsHumanApproval(
                        rule = rule,
                        suggestedReply = replyText,
                        reason = "Rule '${rule.name}' is set to Draft & notify: the reply was drafted for your review, not sent."
                    )
                    "ESCALATE_TO_HUMAN" -> RuleEvaluationResult.NeedsHumanApproval(
                        rule = rule,
                        suggestedReply = replyText,
                        reason = "Rule '${rule.name}' is set to Escalate to human: nothing was sent automatically."
                    )
                    "AUTO_SCHEDULE" -> RuleEvaluationResult.NeedsHumanApproval(
                        rule = rule,
                        suggestedReply = replyText,
                        reason = "Rule '${rule.name}' is set to Auto schedule: replies are drafted for your confirmation instead of being sent or scheduled automatically."
                    )
                    "AUTO_REPLY" -> {
                        // Autonomous reply allowed ONLY IF:
                        // 1. Global AutomationLevel is AUTONOMOUS
                        // 2. Rule does not require human approval
                        // 3. Rule confidence threshold is met
                        // 4. Topic is not sensitive
                        val canAutoReply = automationLevel == AutomationLevel.AUTONOMOUS &&
                                !rule.requireHumanApproval &&
                                rule.confidenceThreshold >= 0.90f

                        if (canAutoReply) {
                            RuleEvaluationResult.AutoReply(rule, replyText, rule.confidenceThreshold)
                        } else {
                            RuleEvaluationResult.NeedsHumanApproval(
                                rule = rule,
                                suggestedReply = replyText,
                                reason = if (automationLevel != AutomationLevel.AUTONOMOUS) {
                                    "Automation level is ${automationLevel.name} (Requires approval)"
                                } else {
                                    "Rule '${rule.name}' requires explicit human verification"
                                }
                            )
                        }
                    }
                    else -> RuleEvaluationResult.NeedsHumanApproval(
                        rule = rule,
                        suggestedReply = replyText,
                        reason = "Rule '${rule.name}' has an unrecognised action ('${rule.actionType}'); the reply was drafted, not sent."
                    )
                }
            }
        }

        // 3. Fallback evaluation via reply engine
        val suggested = replyEngine.generateReply(comment, postContext)
        return if (suggested.isConfident && suggested.confidenceScore >= 0.90f && automationLevel == AutomationLevel.AUTONOMOUS) {
            RuleEvaluationResult.NeedsHumanApproval(
                rule = null,
                suggestedReply = suggested.replyText,
                reason = "Standard AI suggestion ready for review"
            )
        } else {
            RuleEvaluationResult.NeedsHumanApproval(
                rule = null,
                suggestedReply = suggested.replyText,
                reason = suggested.reason
            )
        }
    }
}
