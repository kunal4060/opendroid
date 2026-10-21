package com.opendroid.ai.core.usage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opendroid.ai.core.llm.ProviderCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.usageDataStore: DataStore<Preferences> by preferencesDataStore(name = "usage_stats")

/** A user-edited price for one model, persisted as part of the rate table. */
@Serializable
data class StoredModelRate(
    val provider: String,
    val model: String,
    val inputPer1MUsd: Double,
    val outputPer1MUsd: Double
)

/**
 * Persistent store for B3 usage aggregates and rate overrides.
 *
 * Deliberately DataStore-backed rather than a new Room table: the dashboard
 * needs daily aggregates only, and this keeps the feature out of the shared
 * Room schema/migration chain. Both payloads are small JSON documents; a
 * corrupt payload degrades to "no data yet" instead of crashing metering.
 */
@Singleton
class UsageRepository internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.usageDataStore)

    private val json = Json { ignoreUnknownKeys = true }
    private val usageKey = stringPreferencesKey("daily_usage_json")
    private val ratesKey = stringPreferencesKey("rate_overrides_json")

    val dailyUsage: Flow<List<DailyModelUsage>> = dataStore.data.map { prefs ->
        decodeUsage(prefs[usageKey])
    }

    /** User rate overrides keyed by [CostEstimator.overrideKey]. */
    val rateOverrides: Flow<Map<String, ModelRate>> = dataStore.data.map { prefs ->
        decodeRates(prefs[ratesKey]).associate { stored ->
            CostEstimator.overrideKey(stored.provider, stored.model) to
                ModelRate(stored.inputPer1MUsd, stored.outputPer1MUsd)
        }
    }

    /** The persisted override list itself (provider + model), for the editor UI. */
    val storedRates: Flow<List<StoredModelRate>> = dataStore.data.map { prefs ->
        decodeRates(prefs[ratesKey])
    }

    /**
     * Folds one event into today's aggregate row. The provider name is
     * canonicalised first so an alias can never split one provider's usage
     * across two dashboard rows.
     */
    suspend fun record(event: UsageEvent) {
        val canonical = event.copy(provider = ProviderCatalog.canonicalName(event.provider))
        dataStore.edit { prefs ->
            val current = decodeUsage(prefs[usageKey])
            val updated = UsageAggregator.applyEvent(current, canonical)
            prefs[usageKey] = json.encodeToString(UsageAggregator.prune(updated, LocalDate.now()))
        }
    }

    /** Zeroes every usage aggregate. Rate overrides are user configuration and survive. */
    suspend fun resetUsage() {
        dataStore.edit { prefs -> prefs.remove(usageKey) }
    }

    suspend fun setRateOverride(provider: String, model: String, rate: ModelRate) {
        val canonical = ProviderCatalog.canonicalName(provider)
        dataStore.edit { prefs ->
            val current = decodeRates(prefs[ratesKey])
                .filterNot { it.provider == canonical && it.model == model.trim() }
            val next = current + StoredModelRate(
                provider = canonical,
                model = model.trim(),
                inputPer1MUsd = rate.inputPer1MUsd,
                outputPer1MUsd = rate.outputPer1MUsd
            )
            prefs[ratesKey] = json.encodeToString(next)
        }
    }

    suspend fun clearRateOverride(provider: String, model: String) {
        val canonical = ProviderCatalog.canonicalName(provider)
        dataStore.edit { prefs ->
            val next = decodeRates(prefs[ratesKey])
                .filterNot { it.provider == canonical && it.model == model.trim() }
            prefs[ratesKey] = json.encodeToString(next)
        }
    }

    private fun decodeUsage(raw: String?): List<DailyModelUsage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<DailyModelUsage>>(raw) }.getOrDefault(emptyList())
    }

    private fun decodeRates(raw: String?): List<StoredModelRate> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredModelRate>>(raw) }.getOrDefault(emptyList())
    }
}
