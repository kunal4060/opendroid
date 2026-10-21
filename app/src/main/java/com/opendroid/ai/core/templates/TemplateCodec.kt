package com.opendroid.ai.core.templates

import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Pure encode / decode / validate logic for template files. No Android, no
 * database — everything here is unit-testable on the JVM.
 *
 * Import validation is layered and strict:
 *  1. size cap, JSON must parse to an object;
 *  2. `format` marker must be ours and `schemaVersion` must be supported
 *     (a newer file gets a clear "update the app" error, never a guess);
 *  3. the envelope must decode and be structurally sound (known kind,
 *     non-blank name, non-empty steps, unique step ids, sane step fields);
 *  4. [prepareImport] checks every step's action against the live registry
 *     (via the caller's `stepError`) and resolves name collisions.
 * Only after all four layers pass may the caller write anything.
 */
object TemplateCodec {

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        // The marker fields (format / schemaVersion) equal their defaults, so
        // without this they would be silently omitted from the file — and the
        // importer would reject its own export as unmarked.
        encodeDefaults = true
    }

    /** Param keys (lower-cased, underscores stripped) that name a contact. */
    private val CONTACT_PARAM_KEYS = setOf("contact", "contactname", "recipient")

    /** Param keys (lower-cased, underscores stripped) that name an app. */
    private val APP_PARAM_KEYS = setOf("app", "appname", "package", "packagename")

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    fun encodeMacro(macro: Macro, appVersion: String, exportedAt: Long): TemplateExport {
        val envelope = TemplateEnvelope(
            kind = TemplateFormat.KIND_MACRO,
            name = macro.name,
            exportedAt = exportedAt,
            appVersion = appVersion,
            trigger = macro.trigger,
            steps = macro.steps.map { it.normalisedForTemplate() }
        )
        return TemplateExport(suggestedFileName(macro.name), prettyJson.encodeToString(envelope))
    }

    fun encodePlan(plan: Plan, appVersion: String, exportedAt: Long): TemplateExport {
        val envelope = TemplateEnvelope(
            kind = TemplateFormat.KIND_PLAN,
            name = plan.goal,
            exportedAt = exportedAt,
            appVersion = appVersion,
            estimatedDuration = plan.estimatedDuration,
            steps = plan.steps.map { it.normalisedForTemplate() }
        )
        return TemplateExport(suggestedFileName(plan.goal), prettyJson.encodeToString(envelope))
    }

    /** `Morning Routine` → `morning-routine.opendroid.json`. */
    fun suggestedFileName(name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
            .trim('-')
        return (slug.ifEmpty { "template" }) + "." + TemplateFormat.FILE_EXTENSION
    }

    // ------------------------------------------------------------------
    // Import: decode + structural validation
    // ------------------------------------------------------------------

    fun decode(raw: String): TemplateDecodeResult {
        if (raw.isBlank()) {
            return TemplateDecodeResult.Invalid("The file is empty.")
        }
        if (raw.length > TemplateFormat.MAX_PAYLOAD_CHARS) {
            return TemplateDecodeResult.Invalid(
                "The file is too large to be a template (over ${TemplateFormat.MAX_PAYLOAD_CHARS} characters)."
            )
        }
        val root = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            return TemplateDecodeResult.Invalid("This file is not readable JSON — it is not an OpenDroid template.")
        }
        if (root !is JsonObject) {
            return TemplateDecodeResult.Invalid("This file is not an OpenDroid template (expected a JSON object).")
        }

        val format = (root["format"] as? JsonPrimitive)?.contentOrNull
        if (format == null) {
            return TemplateDecodeResult.Invalid(
                "This file has no OpenDroid template marker — it is not an OpenDroid template file."
            )
        }
        if (format != TemplateFormat.FORMAT_ID) {
            return TemplateDecodeResult.Invalid("Unknown template format '$format' — not an OpenDroid template.")
        }

        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: return TemplateDecodeResult.Invalid("The template has no schema version, so it cannot be verified.")
        if (version > TemplateFormat.CURRENT_SCHEMA_VERSION) {
            return TemplateDecodeResult.Invalid(
                "This template was created by a newer version of OpenDroid (schema v$version). " +
                    "Update the app to import it — nothing was imported."
            )
        }
        if (version < TemplateFormat.MIN_SUPPORTED_SCHEMA_VERSION) {
            return TemplateDecodeResult.Invalid(
                "This template uses unsupported schema v$version — nothing was imported."
            )
        }

        val envelope = try {
            json.decodeFromJsonElement(TemplateEnvelope.serializer(), root)
        } catch (e: SerializationException) {
            return TemplateDecodeResult.Invalid(
                "The template contents are damaged or incomplete: ${e.message ?: "unknown error"}"
            )
        } catch (e: IllegalArgumentException) {
            return TemplateDecodeResult.Invalid(
                "The template contents are damaged or incomplete: ${e.message ?: "unknown error"}"
            )
        }
        return validateEnvelope(envelope)
    }

    private fun validateEnvelope(envelope: TemplateEnvelope): TemplateDecodeResult {
        if (envelope.kind != TemplateFormat.KIND_MACRO && envelope.kind != TemplateFormat.KIND_PLAN) {
            return TemplateDecodeResult.Invalid("Unknown template kind '${envelope.kind}' — nothing was imported.")
        }
        if (envelope.name.isBlank()) {
            return TemplateDecodeResult.Invalid("The template has no name — nothing was imported.")
        }
        if (envelope.steps.isEmpty()) {
            return TemplateDecodeResult.Invalid("The template contains no steps — nothing to import.")
        }
        if (envelope.steps.size > TemplateFormat.MAX_STEPS) {
            return TemplateDecodeResult.Invalid(
                "The template has ${envelope.steps.size} steps — over the ${TemplateFormat.MAX_STEPS}-step limit."
            )
        }
        val seenIds = mutableSetOf<String>()
        for (step in envelope.steps) {
            if (step.stepId.isBlank()) {
                return TemplateDecodeResult.Invalid("A step in the template is missing its id — nothing was imported.")
            }
            if (!seenIds.add(step.stepId)) {
                return TemplateDecodeResult.Invalid(
                    "The template lists step id '${step.stepId}' twice — nothing was imported."
                )
            }
            if (step.action.isBlank()) {
                return TemplateDecodeResult.Invalid(
                    "Step '${step.description}' has no action — nothing was imported."
                )
            }
            if (step.params.size > TemplateFormat.MAX_PARAMS_PER_STEP) {
                return TemplateDecodeResult.Invalid(
                    "Step '${step.description}' has too many parameters — nothing was imported."
                )
            }
        }

        // Normalise for storage: runtime status never travels, and dependency
        // links pointing at steps that are not in the file are dropped (and
        // reported) instead of poisoning the imported entity.
        val warnings = mutableListOf<String>()
        val prepared = envelope.steps.map { step ->
            val unknownDeps = step.dependsOn.filter { it !in seenIds }
            if (unknownDeps.isNotEmpty()) {
                warnings += "Step '${step.description}' links to ${unknownDeps.size} step(s) that are " +
                    "not in this template — those links were dropped."
            }
            step.copy(
                status = StepStatus.PENDING,
                result = null,
                error = null,
                dependsOn = step.dependsOn.filter { it in seenIds }
            )
        }
        warnings += deviceReferenceWarnings(prepared)
        return TemplateDecodeResult.Valid(envelope, prepared, warnings)
    }

    /**
     * Steps that point at data living on the sender's device (a contact name,
     * an app) are flagged, never silently trusted: the reference is carried
     * verbatim and resolved by the existing runtime paths on this device,
     * which fail clearly (or ask the user) instead of crashing.
     */
    private fun deviceReferenceWarnings(steps: List<PlanStep>): List<String> {
        val warnings = mutableListOf<String>()
        for (step in steps) {
            for ((key, value) in step.params) {
                val normalisedKey = key.lowercase().replace("_", "")
                val v = value.trim()
                if (v.isEmpty() || v.startsWith("$")) continue // "$…" = step-output reference, not device data
                when (normalisedKey) {
                    in CONTACT_PARAM_KEYS -> if (!looksLikePhoneNumber(v)) {
                        warnings += "Step '${step.description}' references the contact '$v'. Contacts live " +
                            "on the sender's device, so this is resolved when the template runs here — " +
                            "if the contact is missing you'll be asked."
                    }
                    in APP_PARAM_KEYS -> {
                        warnings += "Step '${step.description}' references the app '$v' — it must be " +
                            "installed on this device for that step to work."
                    }
                }
            }
        }
        return warnings
    }

    /** Same phone-shape heuristic [com.opendroid.ai.core.agent.PlanValidator] uses. */
    private fun looksLikePhoneNumber(value: String): Boolean {
        val cleaned = value.replace(" ", "").replace("-", "")
        return cleaned.startsWith("+") || (cleaned.isNotEmpty() && cleaned.all { it.isDigit() })
    }

    // ------------------------------------------------------------------
    // Import: planning against live state
    // ------------------------------------------------------------------

    /**
     * Final gate before any write. [stepError] is the live action-registry
     * check (PlanValidator.validateStep in production): any step whose action
     * this build does not know blocks the whole import — the file is
     * incompatible with this build, and creating a half-broken macro would be
     * a partial write. Name collisions never overwrite: the import is renamed
     * with an "(imported)" suffix.
     */
    fun prepareImport(
        decoded: TemplateDecodeResult.Valid,
        existingNames: Collection<String>,
        stepError: (PlanStep) -> String?
    ): TemplateImportPlan {
        val errors = mutableListOf<String>()
        decoded.preparedSteps.forEachIndexed { index, step ->
            stepError(step)?.let { error ->
                errors += "Step ${index + 1} ('${step.description}'): $error"
            }
        }
        if (errors.isNotEmpty()) {
            return TemplateImportPlan.Blocked(errors, decoded.warnings)
        }
        return TemplateImportPlan.Ready(
            kind = decoded.envelope.kind,
            resolvedName = resolveName(decoded.envelope.name.trim(), existingNames),
            trigger = decoded.envelope.trigger,
            estimatedDuration = decoded.envelope.estimatedDuration,
            steps = decoded.preparedSteps,
            warnings = decoded.warnings
        )
    }

    /** `Morning Routine` → `Morning Routine (imported)` → `… (imported 2)`. */
    fun resolveName(base: String, existingNames: Collection<String>): String {
        val taken = existingNames.map { it.trim().lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        val first = "$base (imported)"
        if (first.lowercase() !in taken) return first
        var n = 2
        while ("$base (imported $n)".lowercase() in taken) n++
        return "$base (imported $n)"
    }

    /** Runtime state (status/result/error) never travels in a template file. */
    private fun PlanStep.normalisedForTemplate(): PlanStep =
        copy(status = StepStatus.PENDING, result = null, error = null)
}
