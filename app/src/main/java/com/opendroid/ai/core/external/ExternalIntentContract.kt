package com.opendroid.ai.core.external

/**
 * The documented external-automation intent contract (feature C7).
 *
 * External apps (Tasker, MacroDroid), other on-device automation, and ADB can
 * ask OpenDroid to run an agent instruction, run a saved macro, or report the
 * status of the last task — but only through the surface defined here, only
 * while the user has enabled it in Settings → External automation, and only
 * with the user's API token attached. The full human-readable contract,
 * including copy-paste ADB commands, lives in `docs/external-intents.md`;
 * this file is the machine-readable half and the single source of truth for
 * action/extra names.
 *
 * Actions (all app-namespaced):
 *  - [ACTION_RUN_COMMAND]  — run a natural-language instruction through the
 *    normal agent path ([com.opendroid.ai.core.agent.AgentLoop.processQuery]).
 *  - [ACTION_RUN_MACRO]    — run a saved macro's steps through the same
 *    `RUN_MACRO` action the agent itself uses.
 *  - [ACTION_QUERY_STATUS] — report the last external run / last task status.
 *
 * Extras (String unless noted):
 *  - [EXTRA_AUTH_TOKEN]   — REQUIRED on every action. The user-generated API
 *    token from Settings → External automation. Missing or wrong = the
 *    request is rejected and the attempt is written to the task history.
 *  - [EXTRA_INSTRUCTION]  — RUN_COMMAND: the instruction text (required,
 *    max [MAX_INSTRUCTION_LENGTH] chars).
 *  - [EXTRA_SESSION]      — RUN_COMMAND: optional target chat-session id.
 *    Defaults to the dedicated "External commands" session.
 *  - [EXTRA_MACRO_ID] / [EXTRA_MACRO_NAME] — RUN_MACRO: identify the macro;
 *    one of the two is required.
 *  - [EXTRA_REQUEST_ID]   — optional caller-chosen correlation id, echoed in
 *    results and recorded in history.
 *  - [EXTRA_REPLY_PACKAGE] / [EXTRA_REPLY_ACTION] / [EXTRA_REPLY_COMPONENT] —
 *    optional result addressing. When a reply target is given, OpenDroid
 *    sends the outcome as an EXPLICIT broadcast (never an implicit one —
 *    results can contain personal data) with the extras below.
 *
 * Result extras (on the [ACTION_COMMAND_RESULT] broadcast, and as the result
 * intent extras of the activity entry point where the platform allows):
 *  - [EXTRA_RESULT_REQUEST_ID], [EXTRA_RESULT_SUCCESS] ("true"/"false"),
 *    [EXTRA_RESULT_CODE] (machine code: OK, MALFORMED, API_DISABLED,
 *    AUTH_FAILED, APP_LOCKED, NOT_FOUND, EXECUTION_FAILED),
 *    [EXTRA_RESULT_SUMMARY].
 *  - QUERY_STATUS additionally returns [EXTRA_STATUS_STATE],
 *    [EXTRA_STATUS_KIND], [EXTRA_STATUS_LABEL], [EXTRA_STATUS_SUMMARY],
 *    [EXTRA_STATUS_UPDATED_AT] for the last external run, and
 *    [EXTRA_LAST_TASK_DESCRIPTION], [EXTRA_LAST_TASK_ACTION],
 *    [EXTRA_LAST_TASK_SUCCESS], [EXTRA_LAST_TASK_TIMESTAMP] for the most
 *    recent recorded task overall.
 *
 * This object is pure JVM (no Android types) so the parsing rules are
 * unit-testable off-device; the Android entry points adapt an
 * `android.content.Intent` into the extras map consumed by [parse].
 */
object ExternalIntentContract {

    const val ACTION_RUN_COMMAND = "com.opendroid.ai.action.RUN_COMMAND"
    const val ACTION_RUN_MACRO = "com.opendroid.ai.action.RUN_MACRO"
    const val ACTION_QUERY_STATUS = "com.opendroid.ai.action.QUERY_STATUS"
    const val ACTION_COMMAND_RESULT = "com.opendroid.ai.action.COMMAND_RESULT"

    const val EXTRA_AUTH_TOKEN = "auth_token"
    const val EXTRA_INSTRUCTION = "instruction"
    const val EXTRA_SESSION = "session"
    const val EXTRA_MACRO_ID = "macro_id"
    const val EXTRA_MACRO_NAME = "macro_name"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_REPLY_PACKAGE = "reply_package"
    const val EXTRA_REPLY_ACTION = "reply_action"
    const val EXTRA_REPLY_COMPONENT = "reply_component"

    const val EXTRA_RESULT_REQUEST_ID = "request_id"
    const val EXTRA_RESULT_SUCCESS = "success"
    const val EXTRA_RESULT_CODE = "code"
    const val EXTRA_RESULT_SUMMARY = "summary"

    const val EXTRA_STATUS_STATE = "status_state"
    const val EXTRA_STATUS_KIND = "status_kind"
    const val EXTRA_STATUS_LABEL = "status_label"
    const val EXTRA_STATUS_SUMMARY = "status_summary"
    const val EXTRA_STATUS_UPDATED_AT = "status_updated_at"
    const val EXTRA_LAST_TASK_DESCRIPTION = "last_task_description"
    const val EXTRA_LAST_TASK_ACTION = "last_task_action"
    const val EXTRA_LAST_TASK_SUCCESS = "last_task_success"
    const val EXTRA_LAST_TASK_TIMESTAMP = "last_task_timestamp"

    /** Machine outcome codes returned in [EXTRA_RESULT_CODE]. */
    const val CODE_OK = "OK"
    const val CODE_MALFORMED = "MALFORMED"
    const val CODE_API_DISABLED = "API_DISABLED"
    const val CODE_AUTH_FAILED = "AUTH_FAILED"
    const val CODE_APP_LOCKED = "APP_LOCKED"
    const val CODE_NOT_FOUND = "NOT_FOUND"
    const val CODE_EXECUTION_FAILED = "EXECUTION_FAILED"

    /** Status states returned in [EXTRA_STATUS_STATE]. */
    const val STATE_NONE = "NONE"
    const val STATE_RUNNING = "RUNNING"
    const val STATE_AWAITING_APPROVAL = "AWAITING_APPROVAL"
    const val STATE_COMPLETED = "COMPLETED"
    const val STATE_FAILED = "FAILED"

    /** Chat session that external commands land in unless [EXTRA_SESSION] overrides it. */
    const val EXTERNAL_SESSION_ID = "external-commands"
    const val EXTERNAL_SESSION_TITLE = "External commands"

    const val MAX_INSTRUCTION_LENGTH = 2_000
    const val MAX_MACRO_REF_LENGTH = 200
    const val MAX_REQUEST_ID_LENGTH = 100
    const val MAX_SESSION_ID_LENGTH = 100

    /** Where results should be sent back to, if the caller asked for them. */
    data class ReplyTarget(
        /** Explicit component ("package/class") — wins over package+action. */
        val component: String?,
        val packageName: String?,
        /** Result action; defaults to [ACTION_COMMAND_RESULT]. */
        val action: String
    )

    /** A syntactically valid external request (authentication happens later, in the gateway). */
    sealed interface Request {
        val authToken: String?
        val requestId: String?
        val replyTarget: ReplyTarget?

        data class RunCommand(
            val instruction: String,
            val sessionId: String?,
            override val authToken: String?,
            override val requestId: String?,
            override val replyTarget: ReplyTarget?
        ) : Request

        data class RunMacro(
            val macroId: String?,
            val macroName: String?,
            override val authToken: String?,
            override val requestId: String?,
            override val replyTarget: ReplyTarget?
        ) : Request

        data class QueryStatus(
            override val authToken: String?,
            override val requestId: String?,
            override val replyTarget: ReplyTarget?
        ) : Request
    }

    sealed interface ParseResult {
        data class Parsed(val request: Request) : ParseResult

        /** The intent does not conform to the contract; [reason] is safe to log and return. */
        data class Malformed(val reason: String) : ParseResult
    }

    /**
     * Validates the shape of an external intent. Deliberately does NOT check
     * the token's correctness (that needs the stored record) — it only
     * extracts it; the gateway rejects missing/wrong tokens as AUTH_FAILED so
     * "no token" and "wrong token" are indistinguishable to callers.
     */
    fun parse(action: String?, extras: Map<String, String?>): ParseResult {
        if (action == null) return ParseResult.Malformed("missing intent action")
        val token = extras[EXTRA_AUTH_TOKEN]?.takeIf { it.isNotBlank() }
        val requestId = extras[EXTRA_REQUEST_ID]?.trim()?.takeIf { it.isNotEmpty() }
        if (requestId != null && requestId.length > MAX_REQUEST_ID_LENGTH) {
            return ParseResult.Malformed("request_id too long")
        }
        val replyTarget = parseReplyTarget(extras)
        return when (action) {
            ACTION_RUN_COMMAND -> {
                val instruction = extras[EXTRA_INSTRUCTION]?.trim().orEmpty()
                if (instruction.isEmpty()) {
                    return ParseResult.Malformed("RUN_COMMAND requires a non-blank 'instruction' extra")
                }
                if (instruction.length > MAX_INSTRUCTION_LENGTH) {
                    return ParseResult.Malformed("instruction exceeds $MAX_INSTRUCTION_LENGTH characters")
                }
                val sessionId = extras[EXTRA_SESSION]?.trim()?.takeIf { it.isNotEmpty() }
                if (sessionId != null && sessionId.length > MAX_SESSION_ID_LENGTH) {
                    return ParseResult.Malformed("session id too long")
                }
                ParseResult.Parsed(
                    Request.RunCommand(instruction, sessionId, token, requestId, replyTarget)
                )
            }
            ACTION_RUN_MACRO -> {
                val macroId = extras[EXTRA_MACRO_ID]?.trim()?.takeIf { it.isNotEmpty() }
                val macroName = extras[EXTRA_MACRO_NAME]?.trim()?.takeIf { it.isNotEmpty() }
                if (macroId == null && macroName == null) {
                    return ParseResult.Malformed("RUN_MACRO requires a 'macro_id' or 'macro_name' extra")
                }
                if ((macroId?.length ?: 0) > MAX_MACRO_REF_LENGTH ||
                    (macroName?.length ?: 0) > MAX_MACRO_REF_LENGTH
                ) {
                    return ParseResult.Malformed("macro reference too long")
                }
                ParseResult.Parsed(
                    Request.RunMacro(macroId, macroName, token, requestId, replyTarget)
                )
            }
            ACTION_QUERY_STATUS -> ParseResult.Parsed(
                Request.QueryStatus(token, requestId, replyTarget)
            )
            else -> ParseResult.Malformed("unknown action: $action")
        }
    }

    private fun parseReplyTarget(extras: Map<String, String?>): ReplyTarget? {
        val component = extras[EXTRA_REPLY_COMPONENT]?.trim()?.takeIf { it.isNotEmpty() }
        val pkg = extras[EXTRA_REPLY_PACKAGE]?.trim()?.takeIf { it.isNotEmpty() }
        if (component == null && pkg == null) return null
        val action = extras[EXTRA_REPLY_ACTION]?.trim()?.takeIf { it.isNotEmpty() }
            ?: ACTION_COMMAND_RESULT
        return ReplyTarget(component = component, packageName = pkg, action = action)
    }
}
