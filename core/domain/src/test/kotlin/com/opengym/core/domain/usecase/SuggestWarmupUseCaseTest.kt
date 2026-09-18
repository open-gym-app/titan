package com.opengym.core.domain.usecase

import com.opengym.core.domain.integration.FakeExerciseRepository
import com.opengym.core.domain.model.MechanicType
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.warmup.CATALOGUE
import com.opengym.core.domain.warmup.CHEST
import com.opengym.core.domain.warmup.SHOULDERS
import com.opengym.core.domain.warmup.TRICEPS
import com.opengym.core.domain.warmup.lift
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading half of the warm-up suggestion: which stores it consults, and the one decision it
 * makes itself — the setting.
 *
 * What the suggestion *contains* is `WarmupPlannerTest`'s subject; nothing here re-asserts it.
 */
class SuggestWarmupUseCaseTest {

    private val catalogue = FakeExerciseRepository(mobility = CATALOGUE)
    private val settings = FakeUserPreferencesRepository(
        UserPreferences(plateInventory = PlateInventory.METRIC),
    )
    private val suggestWarmup = SuggestWarmupUseCase(catalogue, settings)

    private val pushDay = listOf(
        lift(1, "Bench press", CHEST, secondary = listOf(SHOULDERS, TRICEPS), workingWeight = 80_000_000L),
        lift(2, "Triceps pushdown", TRICEPS, mechanic = MechanicType.ISOLATION, workingWeight = 30_000_000L),
    )

    @Test
    fun `a session gets the muscles it trains warmed up`() =
        runTest {
            val suggestion = suggestWarmup(SESSION, pushDay).first()

            assertTrue(suggestion.hasWarmup)
            assertTrue(suggestion.hasCooldown)
        }

    @Test
    fun `the setting turned off answers with nothing at all`() =
        runTest {
            settings.setWarmupSuggestions(false)

            val suggestion = suggestWarmup(SESSION, pushDay).first()

            assertTrue(!suggestion.hasWarmup)
            assertTrue(!suggestion.hasCooldown)
            assertEquals(emptyList<Any>(), suggestion.rampUp)
        }

    @Test
    fun `turning the setting back on brings the suggestion back`() =
        runTest {
            settings.setWarmupSuggestions(false)
            settings.setWarmupSuggestions(true)

            assertTrue(suggestWarmup(SESSION, pushDay).first().hasWarmup)
        }

    @Test
    fun `an empty session asks the catalogue for nothing it can use`() =
        runTest {
            val suggestion = suggestWarmup(SESSION, emptyList()).first()

            assertTrue(!suggestion.hasWarmup)
        }

    private companion object {
        const val SESSION = 7L
    }
}
