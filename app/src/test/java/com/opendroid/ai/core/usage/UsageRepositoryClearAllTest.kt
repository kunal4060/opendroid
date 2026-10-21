package com.opendroid.ai.core.usage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The A6 wipe relies on [UsageRepository.clearAll] for a genuine first-run
 * state: aggregates AND rate overrides must go. [UsageRepository.resetUsage]
 * keeps its narrower, dashboard-reset semantics (overrides survive) — the
 * distinction is the contract this test pins.
 */
class UsageRepositoryClearAllTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var repository: UsageRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.newFolder(), "usage_stats.preferences_pb")
        }
        repository = UsageRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `clearAll removes usage aggregates and rate overrides`() = runBlocking {
        repository.record(
            UsageEvent(provider = "OpenAI", model = "gpt-test", promptTokens = 10, completionTokens = 5)
        )
        repository.setRateOverride("OpenAI", "gpt-test", ModelRate(1.0, 2.0))
        assertTrue(repository.dailyUsage.first().isNotEmpty())
        assertTrue(repository.storedRates.first().isNotEmpty())

        repository.clearAll()

        assertTrue(repository.dailyUsage.first().isEmpty())
        assertTrue(repository.storedRates.first().isEmpty())
        assertTrue(repository.rateOverrides.first().isEmpty())
    }

    @Test
    fun `resetUsage keeps rate overrides - only the wipe uses clearAll`() = runBlocking {
        repository.record(
            UsageEvent(provider = "OpenAI", model = "gpt-test", promptTokens = 10, completionTokens = 5)
        )
        repository.setRateOverride("OpenAI", "gpt-test", ModelRate(1.0, 2.0))

        repository.resetUsage()

        assertTrue(repository.dailyUsage.first().isEmpty())
        assertTrue(repository.storedRates.first().isNotEmpty())
    }
}
