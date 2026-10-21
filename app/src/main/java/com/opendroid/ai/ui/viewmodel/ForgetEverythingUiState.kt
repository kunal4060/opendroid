package com.opendroid.ai.ui.viewmodel

import com.opendroid.ai.core.privacy.WipeReport
import com.opendroid.ai.core.privacy.WipeStepResult

/** UI state for the Settings danger-zone wipe (A6), driven by `ForgetEverythingUseCase`. */
sealed interface ForgetEverythingUiState {
    data object Idle : ForgetEverythingUiState

    /** The wipe is running; [completedSteps] grows as each storage area finishes. */
    data class Running(val completedSteps: List<WipeStepResult>) : ForgetEverythingUiState

    /** The wipe finished. [WipeReport.fullyErased] decides what the UI may claim. */
    data class Done(val report: WipeReport) : ForgetEverythingUiState
}
