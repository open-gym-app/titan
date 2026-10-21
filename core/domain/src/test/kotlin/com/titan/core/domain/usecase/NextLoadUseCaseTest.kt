package com.titan.core.domain.usecase

import app.cash.turbine.test
import com.titan.core.domain.integration.FakeExerciseRepository
import com.titan.core.domain.load.BENCH_PRESS
import com.titan.core.domain.load.PRESS_UP
import com.titan.core.domain.load.assertSuggestion
import com.titan.core.domain.load.performance
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.plate.kg
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading half of the engine: what it asks the stores for, and when it re-answers. The
 * arithmetic is `NextLoadEngineTest`'s; here the fixtures are the simplest history that tells
 * one branch from another.
 */
class NextLoadUseCaseTest {

    private companion object {
        const val SESSION = 42L
    }

    private val progressionRepository = FakeProgressionRepository()
    private val exerciseRepository = FakeExerciseRepository(listOf(BENCH_PRESS, PRESS_UP))
    private val preferences = FakeUserPreferencesRepository()

    private val useCase = NextLoadUseCase(
        progressionRepository = progressionRepository,
        exerciseRepository = exerciseRepository,
        userPreferencesRepository = preferences,
    )

    @Test
    fun `an exercise with no history yields null`() =
        runTest {
            assertNull(useCase(BENCH_PRESS.id, SESSION).first())
        }

    @Test
    fun `an exercise this catalogue no longer holds yields null`() =
        runTest {
            progressionRepository.setPerformances(99L, performance(kg(100.0) to 6))

            assertNull(useCase(99L, SESSION).first())
        }

    @Test
    fun `the latest performance is judged against the goal in the store`() =
        runTest {
            progressionRepository.setPerformances(BENCH_PRESS.id, performance(kg(100.0) to 6, kg(100.0) to 6))

            assertSuggestion(
                useCase(BENCH_PRESS.id, SESSION).first(),
                weight = kg(102.5),
                reps = 3,
                reason = SuggestionReason.INCREASE,
            )
        }

    @Test
    fun `the history is read two performances deep, for the level gate`() =
        runTest {
            preferences.setFitnessLevel(FitnessLevel.INTERMEDIATE)
            progressionRepository.setPerformances(
                BENCH_PRESS.id,
                performance(kg(100.0) to 6),
                performance(kg(100.0) to 6),
                // A third, which must not be asked for: it is not what the rule reads.
                performance(kg(100.0) to 2),
            )

            assertSuggestion(
                useCase(BENCH_PRESS.id, SESSION).first(),
                weight = kg(102.5),
                reps = 3,
                reason = SuggestionReason.INCREASE,
            )
            assertEquals(listOf(NextLoadUseCase.HISTORY_DEPTH), progressionRepository.requestedLimits)
        }

    @Test
    fun `the session being logged is what the store is told to leave out, and none is passed on as none`() =
        runTest {
            progressionRepository.setPerformances(BENCH_PRESS.id, performance(kg(100.0) to 6))

            useCase(BENCH_PRESS.id, SESSION).first()
            useCase(BENCH_PRESS.id, sessionId = null).first()

            // The exercise detail's call: no workout of its own, so nothing is excluded and the
            // repository is asked exactly that rather than a sentinel it has to know about.
            assertEquals(listOf(SESSION, null), progressionRepository.requestedExclusions)
        }

    @Test
    fun `a bodyweight exercise reads through to a reps-only suggestion`() =
        runTest {
            progressionRepository.setPerformances(PRESS_UP.id, performance(null to 5))

            assertSuggestion(
                useCase(PRESS_UP.id, SESSION).first(),
                weight = null,
                reps = 6,
                reason = SuggestionReason.ADD_REP,
            )
        }

    @Test
    fun `a change of goal re-answers, and a change of theme does not`() =
        runTest {
            progressionRepository.setPerformances(BENCH_PRESS.id, performance(kg(100.0) to 8))

            useCase(BENCH_PRESS.id, SESSION).test {
                // Eight is over the top of the strength range.
                assertSuggestion(awaitItem(), weight = kg(102.5), reps = 3, reason = SuggestionReason.INCREASE)

                // The preferences flow emits, the engine re-runs, the answer is the same: the
                // screen must not hear about it.
                preferences.setTheme(ThemePreference.DARK)
                expectNoEvents()

                // And inside the hypertrophy range, so the answer moves.
                preferences.setTrainingGoal(TrainingGoal.HYPERTROPHY)
                assertSuggestion(awaitItem(), weight = kg(100.0), reps = 9, reason = SuggestionReason.ADD_REP)
            }
        }
}
