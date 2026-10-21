package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.usage.CostEstimator
import com.opendroid.ai.core.usage.ModelPricing
import com.opendroid.ai.core.usage.ModelRate
import com.opendroid.ai.core.usage.UsageAggregator
import com.opendroid.ai.core.usage.UsageRange
import com.opendroid.ai.core.usage.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** One dashboard line: a model's usage in the selected range plus its cost estimate. */
data class UsageRowUi(
    val provider: String,
    val model: String,
    val requests: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val unsplitTokens: Long,
    val estimatedRequests: Long,
    val unsplitRequests: Long,
    /** Null when no price is known for the model — the UI shows "—", never a guess. */
    val costUsd: Double?,
    val isFreeLocal: Boolean,
    /** The rate actually applied (override or built-in), for the price editor. */
    val appliedRate: ModelRate?,
    val hasOverride: Boolean
) {
    val totalTokens: Long get() = promptTokens + completionTokens + unsplitTokens
    /** Cost covers only the split tokens; unsplit tokens are disclosed, not priced. */
    val costExcludesUnsplit: Boolean get() = costUsd != null && unsplitTokens > 0
}

data class UsageDashboardState(
    val range: UsageRange = UsageRange.LAST_30_DAYS,
    val rows: List<UsageRowUi> = emptyList(),
    val totalRequests: Long = 0,
    val totalPromptTokens: Long = 0,
    val totalCompletionTokens: Long = 0,
    val totalUnsplitTokens: Long = 0,
    /** Sum of the rows that have a known price; null when no row is priced. */
    val totalCostUsd: Double? = null,
    val priceTableAsOf: String = ModelPricing.TABLE_AS_OF,
    val priceTableSource: String = ModelPricing.TABLE_SOURCE
) {
    val totalTokens: Long get() = totalPromptTokens + totalCompletionTokens + totalUnsplitTokens
    val hasData: Boolean get() = rows.isNotEmpty()
}

/**
 * Backs the B3 Usage dashboard. All numbers come from the persisted daily
 * aggregates in [UsageRepository] — the same records [com.opendroid.ai.core.usage.UsageRecorder]
 * writes at the provider choke point — so what the dashboard shows is what
 * was actually metered, across restarts.
 */
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val usageRepository: UsageRepository
) : ViewModel() {

    private val selectedRange = MutableStateFlow(UsageRange.LAST_30_DAYS)

    val state: StateFlow<UsageDashboardState> = combine(
        usageRepository.dailyUsage,
        usageRepository.rateOverrides,
        selectedRange
    ) { daily, overrides, range ->
        val inRange = UsageAggregator.filterRange(daily, range, LocalDate.now())
        val summaries = UsageAggregator.summarize(inRange)
        val rows = summaries.map { summary ->
            val rate = CostEstimator.resolveRate(summary.provider, summary.model, overrides)
            UsageRowUi(
                provider = summary.provider,
                model = summary.model,
                requests = summary.requests,
                promptTokens = summary.promptTokens,
                completionTokens = summary.completionTokens,
                unsplitTokens = summary.unsplitTokens,
                estimatedRequests = summary.estimatedRequests,
                unsplitRequests = summary.unsplitRequests,
                costUsd = CostEstimator.costUsd(summary.promptTokens, summary.completionTokens, rate),
                isFreeLocal = ModelPricing.isFreeLocal(summary.provider),
                appliedRate = rate,
                hasOverride = overrides.containsKey(
                    CostEstimator.overrideKey(summary.provider, summary.model)
                )
            )
        }
        UsageDashboardState(
            range = range,
            rows = rows,
            totalRequests = rows.sumOf { it.requests },
            totalPromptTokens = rows.sumOf { it.promptTokens },
            totalCompletionTokens = rows.sumOf { it.completionTokens },
            totalUnsplitTokens = rows.sumOf { it.unsplitTokens },
            totalCostUsd = rows.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageDashboardState())

    fun setRange(range: UsageRange) {
        selectedRange.value = range
    }

    /** Zeroes all recorded usage. The store itself is cleared, not just the UI. */
    fun resetStats() {
        viewModelScope.launch { usageRepository.resetUsage() }
    }

    fun saveRateOverride(provider: String, model: String, inputPer1M: Double, outputPer1M: Double) {
        viewModelScope.launch {
            usageRepository.setRateOverride(provider, model, ModelRate(inputPer1M, outputPer1M))
        }
    }

    fun clearRateOverride(provider: String, model: String) {
        viewModelScope.launch { usageRepository.clearRateOverride(provider, model) }
    }
}
