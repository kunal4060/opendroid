package com.opendroid.ai.core.privacy

import kotlinx.coroutines.CancellationException

/**
 * One storage area the forget-everything wipe (A6) erases.
 *
 * [clear] performs the real deletion for this area and throws on any failure. A target must
 * never swallow its own failure: the whole point of the wipe report is that a store which
 * could not be cleared is named to the user instead of being silently reported as erased.
 */
class WipeTarget(
    val id: String,
    /** Short user-facing name of the storage area, shown in the progress/report UI. */
    val title: String,
    /** What remains on the device if this target fails; shown verbatim in the report. */
    val remainingDescription: String,
    val clear: suspend () -> Unit
)

/** The audited outcome of one [WipeTarget]. */
data class WipeStepResult(
    val targetId: String,
    val title: String,
    val remainingDescription: String,
    val cleared: Boolean,
    /** The failure's message when [cleared] is false; null on success. */
    val errorMessage: String?
)

/** The full wipe outcome. [fullyErased] is the only success claim the UI may make. */
data class WipeReport(
    val steps: List<WipeStepResult>
) {
    val fullyErased: Boolean get() = steps.all { it.cleared }
    val failedSteps: List<WipeStepResult> get() = steps.filter { !it.cleared }
}

/**
 * Runs wipe targets strictly in order, one at a time, and aggregates an honest report.
 *
 * A failing target never stops the run: every remaining area is still attempted, because a
 * half-finished wipe that stops early leaves strictly more user data behind. Cancellation of
 * the calling coroutine is the only thing that aborts the sequence.
 */
class WipeOrchestrator(
    private val targets: List<WipeTarget>
) {
    suspend fun execute(
        onStepFinished: (WipeStepResult) -> Unit = {}
    ): WipeReport {
        val results = ArrayList<WipeStepResult>(targets.size)
        for (target in targets) {
            val result = try {
                target.clear()
                WipeStepResult(
                    targetId = target.id,
                    title = target.title,
                    remainingDescription = target.remainingDescription,
                    cleared = true,
                    errorMessage = null
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                WipeStepResult(
                    targetId = target.id,
                    title = target.title,
                    remainingDescription = target.remainingDescription,
                    cleared = false,
                    errorMessage = failure.message ?: failure.javaClass.simpleName
                )
            }
            results += result
            onStepFinished(result)
        }
        return WipeReport(results)
    }
}
