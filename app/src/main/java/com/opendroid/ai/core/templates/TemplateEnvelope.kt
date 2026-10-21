package com.opendroid.ai.core.templates

import com.opendroid.ai.data.models.PlanStep
import kotlinx.serialization.Serializable

/**
 * C6 — plan / macro template sharing.
 *
 * A template file is a single JSON document (extension `.opendroid.json`,
 * MIME `application/json`) wrapping the exact [PlanStep] list the app already
 * stores in `MacroEntity.stepsJson` / `PlanEntity.stepsJson`. There is no
 * second step schema: `steps` in the envelope is the decoded `stepsJson`
 * payload, verbatim. The wire format is documented in
 * `docs/template-format.md`.
 */
object TemplateFormat {
    /** Value of the envelope's `format` marker. Anything else is not ours. */
    const val FORMAT_ID = "opendroid-template"

    /** The only schema version this build writes. */
    const val CURRENT_SCHEMA_VERSION = 1

    /** Oldest schema version this build can still read. */
    const val MIN_SUPPORTED_SCHEMA_VERSION = 1

    const val KIND_MACRO = "macro"
    const val KIND_PLAN = "plan"

    const val FILE_EXTENSION = "opendroid.json"
    const val MIME_TYPE = "application/json"

    /** Hard caps so a hostile or corrupt file cannot exhaust memory / the DB. */
    const val MAX_PAYLOAD_CHARS = 1_000_000
    const val MAX_STEPS = 500
    const val MAX_PARAMS_PER_STEP = 50
}

/**
 * The versioned envelope written to / read from a template file.
 *
 * [trigger] is macro-only (voice phrase or cron string, exactly as stored);
 * [estimatedDuration] is plan-only. Both default to empty so a v1 file of the
 * other kind still decodes.
 */
@Serializable
data class TemplateEnvelope(
    val format: String = TemplateFormat.FORMAT_ID,
    val schemaVersion: Int = TemplateFormat.CURRENT_SCHEMA_VERSION,
    val kind: String,
    val name: String,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    val trigger: String = "",
    val estimatedDuration: String = "",
    val steps: List<PlanStep> = emptyList(),
    val notes: String? = null
)

/** A payload built for export: the suggested file name plus the JSON text. */
data class TemplateExport(
    val fileName: String,
    val json: String
)

/**
 * Outcome of decoding + structurally validating a template file. No database
 * access has happened at this point.
 */
sealed interface TemplateDecodeResult {
    /**
     * The file is a well-formed template. [preparedSteps] are the envelope's
     * steps normalised for storage (runtime status cleared, dangling
     * `dependsOn` links dropped — each drop is listed in [warnings]).
     */
    data class Valid(
        val envelope: TemplateEnvelope,
        val preparedSteps: List<PlanStep>,
        val warnings: List<String>
    ) : TemplateDecodeResult

    /** The file must be rejected wholesale; [reason] is user-presentable. */
    data class Invalid(val reason: String) : TemplateDecodeResult
}

/**
 * Outcome of planning an import against live state (existing names + the
 * action registry). Still no writes — a [Ready] plan is executed by
 * [TemplateShareService] as a single insert.
 */
sealed interface TemplateImportPlan {
    data class Ready(
        val kind: String,
        val resolvedName: String,
        val trigger: String,
        val estimatedDuration: String,
        val steps: List<PlanStep>,
        val warnings: List<String>
    ) : TemplateImportPlan

    /** Import is refused; [errors] are user-presentable, nothing is created. */
    data class Blocked(
        val errors: List<String>,
        val warnings: List<String>
    ) : TemplateImportPlan
}

/**
 * UI-ready summary of a picked template file, produced by
 * [TemplateShareService.previewImport] before anything is written.
 *
 * [fatalError] is set when the file could not even be decoded (malformed,
 * wrong format, unsupported schema version). Otherwise [errors] lists
 * blocking per-step problems (e.g. actions this build does not know) and
 * [warnings] lists non-blocking ones (device-specific references, dropped
 * dependency links). [canImport] is the single gate the UI obeys.
 */
data class TemplateImportPreview(
    val envelope: TemplateEnvelope?,
    val preparedSteps: List<PlanStep> = emptyList(),
    val resolvedName: String = "",
    val stepSummaries: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val fatalError: String? = null
) {
    val canImport: Boolean
        get() = envelope != null && fatalError == null && errors.isEmpty()
}

/** What a successful import created. */
data class ImportedTemplate(
    val kind: String,
    val id: String,
    val name: String
)
