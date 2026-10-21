package com.opendroid.ai.core.templates

import com.opendroid.ai.BuildConfig
import com.opendroid.ai.core.agent.PlanValidator
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.repository.PlanRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C6 template transfer service: builds export payloads from live entities and
 * turns a picked file back into a real, runnable macro/plan.
 *
 * Import is strictly two-phase. [previewImport] validates everything and
 * writes nothing; [confirmImport] re-validates against fresh DB state and
 * then performs exactly one insert — so a malformed or incompatible file can
 * never leave a partial entity behind. Imported macros land disabled
 * (`isSystem=false`, `isEnabled=false`): template files are untrusted input,
 * the user enables them explicitly. No Room schema change is involved —
 * imports go through the existing [MacroDao] / [PlanRepository].
 */
@Singleton
open class TemplateShareService @Inject constructor(
    private val macroDao: MacroDao,
    private val planRepository: PlanRepository,
    private val planValidator: PlanValidator
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    fun buildMacroExport(macro: Macro): TemplateExport =
        TemplateCodec.encodeMacro(macro, BuildConfig.VERSION_NAME, System.currentTimeMillis())

    fun buildPlanExport(plan: Plan): TemplateExport =
        TemplateCodec.encodePlan(plan, BuildConfig.VERSION_NAME, System.currentTimeMillis())

    /**
     * The live action-registry gate (layer 4 of validation). Open so unit
     * tests can substitute the registry without building the whole action
     * graph — production always uses [PlanValidator.validateStep].
     */
    protected open fun stepError(step: PlanStep): String? = planValidator.validateStep(step)

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /** Validates [raw] end-to-end and returns the preview. Writes nothing. */
    suspend fun previewImport(raw: String): TemplateImportPreview {
        return when (val decoded = TemplateCodec.decode(raw)) {
            is TemplateDecodeResult.Invalid ->
                TemplateImportPreview(envelope = null, fatalError = decoded.reason)
            is TemplateDecodeResult.Valid -> {
                when (val plan = TemplateCodec.prepareImport(decoded, existingNames(decoded.envelope.kind)) { stepError(it) }) {
                    is TemplateImportPlan.Ready -> TemplateImportPreview(
                        envelope = decoded.envelope,
                        preparedSteps = decoded.preparedSteps,
                        resolvedName = plan.resolvedName,
                        stepSummaries = plan.steps.map { "${it.order}. ${it.description} [${it.action}]" },
                        warnings = plan.warnings
                    )
                    is TemplateImportPlan.Blocked -> TemplateImportPreview(
                        envelope = decoded.envelope,
                        preparedSteps = decoded.preparedSteps,
                        resolvedName = decoded.envelope.name,
                        stepSummaries = decoded.preparedSteps.map { "${it.order}. ${it.description} [${it.action}]" },
                        warnings = plan.warnings,
                        errors = plan.errors
                    )
                }
            }
        }
    }

    /**
     * Executes a previously previewed import as a single insert. Re-runs the
     * full planning gate against fresh state first, so a stale preview (names
     * changed since, actions unregistered) fails cleanly instead of writing.
     *
     * @throws IllegalStateException when the preview is not importable.
     */
    suspend fun confirmImport(preview: TemplateImportPreview): ImportedTemplate {
        val envelope = preview.envelope
            ?: throw IllegalStateException(preview.fatalError ?: "Nothing to import.")
        val rechecked = TemplateCodec.prepareImport(
            TemplateDecodeResult.Valid(envelope, preview.preparedSteps, preview.warnings),
            existingNames(envelope.kind)
        ) { stepError(it) }
        val ready = rechecked as? TemplateImportPlan.Ready
            ?: throw IllegalStateException(
                "This template can no longer be imported: " +
                    (rechecked as TemplateImportPlan.Blocked).errors.joinToString("; ")
            )
        return when (ready.kind) {
            TemplateFormat.KIND_MACRO -> {
                val id = UUID.randomUUID().toString()
                macroDao.insertMacro(
                    MacroEntity(
                        id = id,
                        name = ready.resolvedName,
                        trigger = ready.trigger,
                        stepsJson = json.encodeToString(ready.steps),
                        isSystem = false,
                        isEnabled = false
                    )
                )
                ImportedTemplate(ready.kind, id, ready.resolvedName)
            }
            else -> {
                val id = UUID.randomUUID().toString()
                planRepository.savePlan(
                    Plan(
                        planId = id,
                        goal = ready.resolvedName,
                        estimatedDuration = ready.estimatedDuration,
                        estimatedSteps = ready.steps.size,
                        steps = ready.steps,
                        status = PlanStatus.PENDING,
                        createdAt = System.currentTimeMillis()
                    )
                )
                ImportedTemplate(ready.kind, id, ready.resolvedName)
            }
        }
    }

    private suspend fun existingNames(kind: String): List<String> = when (kind) {
        TemplateFormat.KIND_MACRO -> macroDao.getAllMacros().map { it.name }
        else -> planRepository.allPlans.first().map { it.goal }
    }
}
