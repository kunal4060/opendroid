package com.opendroid.ai.core.agent

import android.content.Context
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.data.repository.PlanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanManager @Inject constructor(
    private val planRepository: PlanRepository,
    private val workingMemory: WorkingMemory,
    private val planValidator: dagger.Lazy<PlanValidator>
) {
    private val _currentPlan = MutableStateFlow<Plan?>(null)
    val currentPlan: StateFlow<Plan?> = _currentPlan.asStateFlow()

    /**
     * Guards every read-modify-write of [_currentPlan]. There are now two
     * independent writers - AgentLoop's executePlanLoop coroutine driving
     * execution, and the Plan tab editor mutating pending steps from the UI
     * - and every mutator below reads `_currentPlan.value`, derives a new
     * Plan from it, and writes the result back. Without a lock held across
     * that whole read+write, two concurrent mutators can both read the same
     * stale snapshot and the later write silently discards the other (e.g.
     * a step that just finished reverting to RUNNING because a concurrent
     * param edit was computed from the pre-completion snapshot).
     */
    private val mutex = Mutex()

    /**
     * Used only by [clearPlan]. That function must stay non-suspend because
     * it is invoked synchronously from AgentLoop.rejectProposedPlan() (not a
     * suspend function), so it can't take [mutex] with `withLock` directly
     * on the caller's thread. clearPlan() has nothing to compute from the
     * previous value - it unconditionally nulls the plan - so it hops onto
     * this scope to acquire the mutex before writing, which keeps it
     * serialized with every other mutator instead of racing them with an
     * unguarded assignment.
     */
    private val scope = CoroutineScope(Dispatchers.Default)

    suspend fun startNewPlan(
        plan: Plan,
        context: Context,
        initialStatus: PlanStatus = PlanStatus.RUNNING
    ) {
        val validatedPlan = planValidator.get().validateAndFix(plan, context)
        mutex.withLock {
            val initialPlan = validatedPlan.copy(status = initialStatus)
            _currentPlan.value = initialPlan
            workingMemory.activePlan = initialPlan
            saveCurrentPlanLocked()
        }
    }

    suspend fun updateStepStatus(stepId: String, status: StepStatus, result: String? = null, error: String? = null) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) {
                    step.copy(status = status, result = result, error = error)
                } else {
                    step
                }
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    suspend fun updatePlanStatus(status: PlanStatus) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val updatedPlan = plan.copy(status = status)
            _currentPlan.value = updatedPlan
            if (status == PlanStatus.COMPLETED || status == PlanStatus.FAILED) {
                workingMemory.activePlan = null
            } else {
                workingMemory.activePlan = updatedPlan
            }
            saveCurrentPlanLocked()
        }
    }

    suspend fun loadPlan(planId: String): Boolean {
        val plan = planRepository.getPlanById(planId) ?: return false
        mutex.withLock {
            _currentPlan.value = plan
            workingMemory.activePlan = plan
        }
        return true
    }

    suspend fun saveCurrentPlan() {
        mutex.withLock {
            saveCurrentPlanLocked()
        }
    }

    /**
     * Persists whatever is currently in [_currentPlan]. Must only be called
     * while holding [mutex] so the value it reads can never be a
     * half-applied write from a concurrent mutator.
     */
    private suspend fun saveCurrentPlanLocked() {
        val plan = _currentPlan.value ?: return
        planRepository.savePlan(plan)
    }

    /**
     * Clears the current plan. [expectedPlanId], when non-null, pins the
     * clear to that plan (mirrors [cancelPlan]'s guard): this hops onto
     * [scope] to take the mutex, so it can run arbitrarily later than the
     * call — by then a brand-new, unrelated plan may already have replaced
     * [_currentPlan] via [startNewPlan], and an unpinned clear would erase
     * that new plan mid-flight (audit-2 #6). Callers rejecting a specific
     * proposal pass its id; passing null keeps the old "clear whatever is
     * current" behaviour.
     */
    fun clearPlan(expectedPlanId: String? = null) {
        scope.launch {
            mutex.withLock {
                if (expectedPlanId != null && _currentPlan.value?.planId != expectedPlanId) {
                    return@withLock
                }
                _currentPlan.value = null
                workingMemory.activePlan = null
            }
        }
    }

    /**
     * Marks the current plan (if any) as cancelled and clears it from working
     * memory. Safe to call when there is no active plan, and a no-op when the
     * plan sitting in [_currentPlan] has already reached a terminal status
     * (COMPLETED, FAILED, or CANCELLED).
     *
     * That terminal-status guard is load-bearing, not defensive dressing: unlike
     * every other mutator here, callers of cancelPlan() in AgentLoop (see
     * cancelCurrentTask, resolveStaleProposedPlan, abandonWaitingTask) don't
     * necessarily know whether a task is genuinely still running - e.g.
     * cancelCurrentTask() is documented as "safe to call when nothing is
     * running", and when nothing was running, whatever plan is still parked in
     * [_currentPlan] is the *last finished* plan (PlanScreen intentionally keeps
     * showing it there after completion - see updatePlanStatus). Without this
     * guard, a call with nothing actually in flight would relabel that finished
     * plan CANCELLED and persist that lie over its real COMPLETED/FAILED row.
     * Once a plan has reached a terminal status, it is done - nothing may ever
     * move it to a different status again.
     *
     * [expectedPlanId], when non-null, adds a second, independent guard: this
     * only applies CANCELLED when the plan currently held here is STILL the one
     * identified by that id. AgentLoop's cancellation paths (cancelCurrentTask,
     * abandonWaitingTask, resolveStaleProposedPlan) fire their join-then-cancel
     * follow-up on a separate coroutine that can run arbitrarily later than the
     * moment cancellation was actually requested - by then a brand-new,
     * completely unrelated task may already have replaced [_currentPlan] via
     * [startNewPlan]. Without this check, cancelPlan() would happily relabel
     * that new plan CANCELLED (the terminal-status guard above does NOT catch
     * this - a fresh plan starts RUNNING, not terminal) purely because it
     * happened to be whatever was "current" when this coroutine got around to
     * running. Passing null keeps the old "cancel whatever is current"
     * behaviour for any caller that genuinely means that.
     */
    suspend fun cancelPlan(expectedPlanId: String? = null) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            if (plan.status.isTerminal()) return@withLock
            if (expectedPlanId != null && plan.planId != expectedPlanId) return@withLock
            val updatedPlan = plan.copy(status = PlanStatus.CANCELLED)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = null
            saveCurrentPlanLocked()
        }
    }

    /**
     * Updates the description of a step that has not started executing yet.
     * No-op if the step cannot be found or is no longer PENDING, so an
     * in-flight or already-finished step can never be mutated out from
     * under the executor.
     */
    suspend fun updateStepDescription(stepId: String, description: String) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) step.copy(description = description) else step
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    /**
     * Replaces the params map of a step that has not started executing yet.
     * No-op if the step cannot be found or is no longer PENDING. Executing
     * this while the plan is RUNNING lets executePlanLoop pick up the new
     * params the next time it reads this step via getActiveStep().
     */
    suspend fun updateStepParams(stepId: String, params: Map<String, String>) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) step.copy(params = params) else step
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    /**
     * Atomically updates both the description and params of a step that has not started
     * executing yet, in a single mutex-guarded read-modify-write. Prefer this over calling
     * [updateStepDescription] then [updateStepParams] back to back: those are two
     * independent suspend calls, so a concurrent reader (executePlanLoop's
     * [getStepSnapshot], or anything else observing [currentPlan]) can land between them
     * and see the new description paired with the still-old params - a half-applied edit
     * that then gets persisted and executed as if it were what the user asked for. No-op
     * if the step cannot be found or is no longer PENDING.
     */
    suspend fun updateStep(stepId: String, description: String, params: Map<String, String>) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) step.copy(description = description, params = params) else step
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    /**
     * Removes a step that has not started executing yet from the current
     * plan. No-op if the step cannot be found or is no longer PENDING.
     */
    suspend fun removeStep(stepId: String) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            // Audit-2 #5: strip the removed id from every dependent's
            // dependsOn — a dependency on a step that no longer exists can
            // never be met (see hasDependenciesMet), so leaving it behind
            // stranded the dependents as PENDING forever while the plan
            // around them reported completion.
            val updatedSteps = plan.steps
                .filterNot { it.stepId == stepId }
                .map { step ->
                    if (stepId in step.dependsOn) {
                        step.copy(dependsOn = step.dependsOn - stepId)
                    } else {
                        step
                    }
                }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    /**
     * Re-reads a single step's current description/params/action directly from the
     * source of truth. Meant to be called by executePlanLoop immediately before it
     * dispatches a step, to close the window between its earlier (unguarded)
     * [getActiveStep] snapshot and the actual dispatch - the Plan-tab step editor
     * (updateStepDescription then updateStepParams) can land in that window, and
     * without this re-read its edit would be persisted to the DB yet silently ignored
     * by the execution already in flight.
     *
     * Mutex-guarded like the mutators above, so it always observes either a fully
     * applied edit or none of it, never a half-applied intermediate write. Must NOT be
     * called from within a block that already holds [mutex] - see its doc comment -
     * that would deadlock.
     */
    suspend fun getStepSnapshot(stepId: String): PlanStep? {
        mutex.withLock {
            return _currentPlan.value?.steps?.find { it.stepId == stepId }
        }
    }

    /**
     * Not mutex-guarded: this only takes a single snapshot of the
     * StateFlow's current value and reads from that local, immutable [Plan]
     * (steps is a List<PlanStep> of immutable data classes), so there is no
     * read-modify-write here to race - just one atomic read of a reference
     * that can never be torn.
     */
    fun getActiveStep(): PlanStep? {
        val plan = _currentPlan.value ?: return null
        if (plan.status != PlanStatus.RUNNING) return null
        // Return the first pending step that doesn't have pending/running dependencies
        return plan.steps.firstOrNull { step ->
            step.status == StepStatus.PENDING && hasDependenciesMet(step, plan.steps)
        }
    }

    /**
     * A plan in one of these statuses is done: nothing may ever relabel it into a
     * different status again. See [cancelPlan].
     */
    private fun PlanStatus.isTerminal(): Boolean =
        this == PlanStatus.COMPLETED || this == PlanStatus.FAILED || this == PlanStatus.CANCELLED

    private fun hasDependenciesMet(step: PlanStep, allSteps: List<PlanStep>): Boolean {
        if (step.dependsOn.isEmpty()) return true
        // A dependency is met when it has finished executing (COMPLETED or FAILED).
        // Only PENDING or RUNNING dependencies should block execution.
        // Previously this required COMPLETED, which meant a step with dependsOn
        // would be stuck forever if the dependency succeeded but returned no data
        // (since the dependency check was too strict).
        //
        // Loop-2 Area2 #5: a FAILED dependency is the exception. The
        // dependsOn entries that survive validation are data-flow edges —
        // the dependent's params reference the dependency's result as
        // $depId, and ActionSequenceExecutor.resolveParameters substitutes
        // only COMPLETED steps' results. Counting a FAILED dependency as
        // met therefore dispatched the dependent with the literal "$depId"
        // placeholder as its parameter value (a real message whose body
        // was "$s1", then recorded COMPLETED). A FAILED dependency whose
        // result this step actually references is UNMET: getActiveStep
        // skips the step, the post-failure re-evaluation gets its chance to
        // rewrite it (MODIFY), and if nothing does, the stranded-step
        // handling in executePlanLoop fails it honestly. Ordering-only
        // dependencies (no $depId reference in params) keep the
        // finished-is-met behaviour above.
        return step.dependsOn.all { depId ->
            val depStep = allSteps.find { it.stepId == depId }
            when {
                depStep == null -> false
                depStep.status == StepStatus.COMPLETED -> true
                depStep.status == StepStatus.FAILED -> !referencesStepResult(step.params, depId)
                else -> false
            }
        }
    }

    /**
     * True when any param value references [stepId]'s result — the same
     * literal "$stepId" form resolveParameters substitutes (the "$$stepId"
     * form contains it as a substring, so one check covers both).
     */
    private fun referencesStepResult(params: Map<String, String>, stepId: String): Boolean {
        val reference = "$" + stepId
        return params.values.any { it.contains(reference) }
    }
}
