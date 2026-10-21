package com.opendroid.ai.core.llm

import kotlin.math.max

/**
 * Storage-fit verdict for downloading one on-device model onto this device.
 *
 * - [BLOCK]: the model is larger than the free space — the download cannot succeed.
 * - [WARN]: the download fits, but would leave less than the safe margin free.
 * - [OK]: the download fits with the safe margin to spare.
 */
enum class ModelFitVerdict { OK, WARN, BLOCK }

/**
 * One fit assessment for a model on a specific device. Every number is real:
 * [modelBytes] comes from `OnDeviceModelSpec.expectedSize` (the exact byte count
 * `ModelDownloadWorker` enforces, or the downloaded entity's recorded size) and
 * [freeBytes] / [totalStorageBytes] from the device's data volume — there is no
 * second size table anywhere.
 */
data class ModelFitAssessment(
    val verdict: ModelFitVerdict,
    /** Bytes that still have to be fetched (full size minus any partial download). */
    val modelBytes: Long,
    /** Free bytes on the volume the model will land on, measured now. */
    val freeBytes: Long,
    /** Total bytes of that volume. */
    val totalStorageBytes: Long,
    /** Free bytes that would remain after the download completes. */
    val remainingAfterBytes: Long,
    /** RAM the registry's existing heuristic says this model needs (0 = no requirement). */
    val requiredRamBytes: Long,
    /** Total RAM of this device (0 when unknown). */
    val totalRamBytes: Long,
    /** True when [totalRamBytes] meets [requiredRamBytes] (or RAM is unknown). */
    val ramSufficient: Boolean
)

/**
 * Typed result of the download gate that every model download traverses
 * (see `ModelRepository.startDownload`). [Blocked] and [NeedsConfirmation] both
 * refuse the download; they differ only in whether user consent can lift them.
 */
sealed interface ModelDownloadGate {
    data object Proceed : ModelDownloadGate
    data class NeedsConfirmation(val assessment: ModelFitAssessment) : ModelDownloadGate
    data class Blocked(val assessment: ModelFitAssessment) : ModelDownloadGate
}

/**
 * The single storage/RAM fit policy for on-device model downloads (checklist A9).
 * Pure and JVM-testable: device values are passed in, never read here.
 */
object ModelFitPolicy {

    /** Never let a model download leave less than this much space free without a warning. */
    const val MIN_SAFE_REMAINING_BYTES: Long = 1_073_741_824L // 1 GiB

    /** On large volumes the safe margin scales: 5% of total storage. */
    private const val SAFE_MARGIN_DIVISOR: Long = 20L // total / 20 = 5%

    fun minSafeRemainingBytes(totalStorageBytes: Long): Long =
        max(MIN_SAFE_REMAINING_BYTES, totalStorageBytes / SAFE_MARGIN_DIVISOR)

    /**
     * @param modelBytes bytes still to fetch (for a fresh download: the model's full size).
     * @param freeBytes free bytes on the target volume right now.
     * @param totalStorageBytes total bytes of the target volume.
     * @param requiredRamBytes RAM requirement from the registry heuristic (0 = none).
     * @param totalRamBytes device total RAM (0 = unknown; treated as sufficient).
     */
    fun assess(
        modelBytes: Long,
        freeBytes: Long,
        totalStorageBytes: Long,
        requiredRamBytes: Long = 0L,
        totalRamBytes: Long = 0L
    ): ModelFitAssessment {
        val size = modelBytes.coerceAtLeast(0L)
        val free = freeBytes.coerceAtLeast(0L)
        val remaining = free - size
        val verdict = when {
            size > free -> ModelFitVerdict.BLOCK
            remaining < minSafeRemainingBytes(totalStorageBytes) -> ModelFitVerdict.WARN
            else -> ModelFitVerdict.OK
        }
        return ModelFitAssessment(
            verdict = verdict,
            modelBytes = size,
            freeBytes = free,
            totalStorageBytes = totalStorageBytes.coerceAtLeast(0L),
            remainingAfterBytes = remaining,
            requiredRamBytes = requiredRamBytes.coerceAtLeast(0L),
            totalRamBytes = totalRamBytes.coerceAtLeast(0L),
            ramSufficient = totalRamBytes <= 0L || requiredRamBytes <= 0L ||
                totalRamBytes >= requiredRamBytes
        )
    }

    /** Maps an assessment to the gate decision enforced by the download funnel. */
    fun gate(assessment: ModelFitAssessment): ModelDownloadGate = when (assessment.verdict) {
        ModelFitVerdict.BLOCK -> ModelDownloadGate.Blocked(assessment)
        ModelFitVerdict.WARN -> ModelDownloadGate.NeedsConfirmation(assessment)
        ModelFitVerdict.OK -> ModelDownloadGate.Proceed
    }
}
