package com.opendroid.ai.core.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanValidator @Inject constructor(
    private val actionDispatcher: dagger.Lazy<ActionDispatcher>,
    private val unknownActionDao: dagger.Lazy<UnknownActionDao>
) {

    companion object {
        private val DATA_PRODUCING_ACTIONS = setOf(
            "GET_DIRECTIONS", "GET_WEATHER", "GET_NEWS", "CALCULATE",
            "CURRENCY_CONVERT", "TRANSLATE", "WEB_SEARCH", "SUMMARIZE_URL",
            "CHECK_STOCK", "DEFINE_WORD", "CONVERT_UNITS", "FACT_CHECK",
            "GET_SYSTEM_INFO", "CHECK_TRAFFIC", "CHECK_FLIGHT", "TRACK_DELIVERY",
            "CHECK_BALANCE", "LIST_CALENDAR_TODAY", "LIST_CALENDAR_WEEK",
            "READ_MESSAGES", "READ_EMAILS", "READ_NOTES", "READ_FILE",
            "LIST_FILES", "GET_SCREEN_TEXT", "LIST_INSTALLED_APPS",
            "ASK_USER", "SPLIT_BILL", "ANALYZE_SCREENSHOT",
            "READ_AND_REMEMBER_SCREEN", "RECALL_MEMORY", "QUERY_KNOWLEDGE_GRAPH"
        )
    }

    fun validatePlan(plan: Plan): List<String> {
        val errors = mutableListOf<String>()
        for (step in plan.steps) {
            val err = validateStep(step)
            if (err != null) errors.add(err)
        }
        return errors
    }

    fun validateStep(step: PlanStep): String? {
        if (!actionDispatcher.get().isRegistered(step.action)) {
            return "Action '${step.action}' is not registered."
        }
        return null
    }

    suspend fun validateAndFix(plan: Plan, context: Context): Plan {
        val finalSteps = mutableListOf<PlanStep>()
        var currentOrder = 1

        for (step in plan.steps) {
            var updatedStep = step
            val isReg = actionDispatcher.get().isRegistered(step.action)

            if (!isReg) {
                when (step.action.uppercase()) {
                    "VERIFY_APP", "SECURITY_CHECK" -> {
                        updatedStep = step.copy(action = "GET_SYSTEM_INFO")
                        logUnknownAction(step.action, plan.goal, "AUTO_FIXED")
                    }
                    "LAUNCH_APP", "OPEN_APP_OR_WEBSITE" -> {
                        val isWebsite = step.action == "OPEN_APP_OR_WEBSITE" && (
                            step.params.containsKey("url") ||
                            step.params.containsKey("website") ||
                            step.params.containsKey("link") ||
                            step.params.values.any { it.startsWith("http") }
                        )
                        if (isWebsite) {
                            val urlValue = step.params["url"]
                                ?: step.params["website"]
                                ?: step.params["link"]
                                ?: step.params.values.firstOrNull { it.startsWith("http") }
                                ?: ""
                            updatedStep = step.copy(action = "SUMMARIZE_URL", params = mapOf("url" to urlValue))
                        } else {
                            val appNameValue = step.params["appName"]
                                ?: step.params["app"]
                                ?: step.params["packageName"]
                                ?: step.params["package"]
                                ?: ""
                            updatedStep = step.copy(action = "OPEN_APP", params = mapOf("appName" to appNameValue))
                        }
                        logUnknownAction(step.action, plan.goal, "AUTO_FIXED")
                    }
                    else -> {
                        logUnknownAction(step.action, plan.goal, "FAILED")
                    }
                }
            }

            val commActions = listOf("SEND_WHATSAPP", "SEND_TELEGRAM", "MAKE_CALL", "SEND_SMS", "MAKE_VIDEO_CALL")
            if (commActions.contains(updatedStep.action.uppercase()) && updatedStep.params.containsKey("contact")) {
                val contactName = updatedStep.params["contact"] ?: ""
                if (contactName.isNotEmpty() && !isPhoneNumber(contactName) && !contactName.startsWith("@")) {
                    val resolvedPhone = resolveContactToPhoneNumber(context, contactName)
                    if (resolvedPhone != null) {
                        val updatedParams = updatedStep.params.toMutableMap().apply { put("contact", resolvedPhone) }
                        updatedStep = updatedStep.copy(order = currentOrder++, params = updatedParams)
                        finalSteps.add(updatedStep)
                    } else if (updatedStep.action.uppercase() == "SEND_TELEGRAM") {
                        // For Telegram, a contact name could also be a Telegram username directly
                        finalSteps.add(updatedStep.copy(order = currentOrder++))
                    } else {
                        val askStepId = "${updatedStep.stepId}_ask"
                        val askStep = PlanStep(
                            stepId = askStepId, order = currentOrder++,
                            description = "Ask user for contact number of '$contactName'",
                            action = "ASK_USER",
                            params = mapOf("question" to "I couldn't find a contact named '$contactName'. What is their phone number?"),
                            fallback = ""
                        )
                        finalSteps.add(askStep)
                        val updatedParams = updatedStep.params.toMutableMap().apply { put("contact", "$$askStepId") }
                        val updatedDependsOn = updatedStep.dependsOn.toMutableList().apply { if (!contains(askStepId)) add(askStepId) }
                        updatedStep = updatedStep.copy(order = currentOrder++, params = updatedParams, dependsOn = updatedDependsOn)
                        finalSteps.add(updatedStep)
                    }
                } else {
                    updatedStep = updatedStep.copy(order = currentOrder++)
                    finalSteps.add(updatedStep)
                }
            } else {
                updatedStep = updatedStep.copy(order = currentOrder++)
                finalSteps.add(updatedStep)
            }
        }

        val cleanedSteps = ensureUniqueStepIds(removeBadDependencies(finalSteps))
        return plan.copy(steps = cleanedSteps, estimatedSteps = cleanedSteps.size)
    }

    /**
     * Audit-2 #7: stepIds are the join key for status updates, `$stepId`
     * parameter references and dependency lookups, so duplicates corrupt
     * execution (a status update hits every step sharing the id while
     * readers only ever find the first). Suffix later occurrences
     * deterministically; references to the original id bind to the first
     * occurrence. (AgentLoop.normalizePlan applies the same rule to freshly
     * parsed plans; this covers plans reaching startNewPlan by other paths.)
     */
    private fun ensureUniqueStepIds(steps: List<PlanStep>): List<PlanStep> {
        val seen = mutableMapOf<String, Int>()
        return steps.map { step ->
            val count = (seen[step.stepId] ?: 0) + 1
            seen[step.stepId] = count
            if (count == 1) step else step.copy(stepId = "${step.stepId}_$count")
        }
    }

    private fun removeBadDependencies(steps: List<PlanStep>): List<PlanStep> {
        return steps.map { step ->
            if (step.dependsOn.isEmpty()) return@map step
            val trueDeps = step.dependsOn.filter { depId ->
                val depStep = steps.find { it.stepId == depId }
                depStep != null && DATA_PRODUCING_ACTIONS.contains(depStep.action.uppercase())
            }
            step.copy(dependsOn = trueDeps)
        }
    }

    private suspend fun logUnknownAction(attemptedAction: String, goal: String, fixStatus: String) {
        try {
            unknownActionDao.get().insertUnknownAction(
                UnknownActionEntity(attemptedAction = attemptedAction, goal = goal, fixStatus = fixStatus)
            )
        } catch (e: Exception) { }
    }

    private fun isPhoneNumber(contact: String): Boolean {
        val cleaned = contact.replace(" ", "").replace("-", "")
        return cleaned.startsWith("+") || (cleaned.isNotEmpty() && cleaned.all { it.isDigit() })
    }

    // lint false positive: both cursors are closed by `?.use { }` on every path,
    // but the Recycle detector does not model Kotlin's use() inlining. See #67.
    @Suppress("Recycle")
    private fun resolveContactToPhoneNumber(context: Context, contact: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        try {
            val contentResolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)

            // Audit-2 #3: collect EVERY matching number and auto-resolve
            // only when exactly one DISTINCT number matches. The previous
            // moveToFirst() silently bound the plan to whichever row the
            // cursor returned first, so "message Alex" could be rewritten
            // to the wrong Alex's number before the approval sheet ever
            // showed — the sheet displays the step description, not the
            // substituted number, so the user approved blind. Ambiguity now
            // returns null, leaving the name in place for the caller's
            // ASK_USER / contact-picker flow to disambiguate.
            fun queryNumbers(selection: String, arg: String): Set<String> {
                val numbers = linkedSetOf<String>()
                contentResolver.query(uri, projection, selection, arrayOf(arg), null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (idx >= 0) {
                        while (cursor.moveToNext()) {
                            val number = cursor.getString(idx)
                            if (!number.isNullOrBlank()) {
                                numbers += number.replace(" ", "").replace("-", "")
                            }
                        }
                    }
                }
                return numbers
            }

            val selectionExact = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
            val exactNumbers = queryNumbers(selectionExact, contact.trim())
            if (exactNumbers.size == 1) return exactNumbers.first()
            if (exactNumbers.size > 1) return null

            val selectionLike = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val likeNumbers = queryNumbers(selectionLike, "%${contact.trim()}%")
            if (likeNumbers.size == 1) return likeNumbers.first()
        } catch (e: Exception) { }
        return null
    }
}
