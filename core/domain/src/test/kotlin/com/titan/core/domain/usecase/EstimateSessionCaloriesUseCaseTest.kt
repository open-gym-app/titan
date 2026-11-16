package com.titan.core.domain.usecase

import com.titan.core.domain.integration.FakeExerciseRepository
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.repository.FakeBodyMeasurementRepository
import com.titan.core.domain.stats.CalorieEstimate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * That the use case hands [CalorieEstimate] the right inputs: the session's exercises, their
 * catalogue entries, the user's own default rest and the weight they had that day. The pricing
 * itself is `CalorieEstimateTest`'s.
 */
class EstimateSessionCaloriesUseCaseTest {

    private val sessions = FakeSessionRepository()
    private val press = Exercise(id = 1, name = "Press", type = ExerciseType.STRENGTH)
    private val run = Exercise(id = 2, name = "Run", type = ExerciseType.ENDURANCE)

    private fun useCase(
        measurements: List<BodyMeasurement> = emptyList(),
        defaultRest: Long = 60,
    ) = EstimateSessionCaloriesUseCase(
        sessionRepository = sessions,
        exerciseRepository = FakeExerciseRepository(listOf(press, run)),
        userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(defaultRestDuration = defaultRest.seconds),
        ),
        bodyMeasurementRepository = FakeBodyMeasurementRepository(measurements),
    )

    private suspend fun performed(sessionId: Long): List<WorkoutSet> = sessions.getSetsForSession(sessionId).first()

    @Test
    fun `a session is priced on the user's default rest and their weight that day`() =
        runTest {
            val sessionId = 1L
            val pressId = sessions.addExercise(sessionId, exerciseId = press.id)
            repeat(2) { sessions.addSet(pressId, reps = 10) }
            val history = listOf(
                BodyMeasurement(weight = 70_000_000L, createdAt = 1_000, updatedAt = 1_000),
                BodyMeasurement(weight = 99_000_000L, createdAt = 9_000, updatedAt = 9_000),
            )

            val estimate = useCase(history, defaultRest = 90)(sessionId, performed(sessionId), at = 5_000)

            // Two sets of ten and the one rest between them, at 70 kg.
            val expected = CalorieEstimate.STRENGTH_MET * 70 * (2 * 30 + 90) / 3_600
            assertEquals(expected.roundToInt(), estimate)
        }

    @Test
    fun `the catalogue's type decides the rate`() =
        runTest {
            val sessionId = 1L
            val runId = sessions.addExercise(sessionId, exerciseId = run.id)
            sessions.addSet(
                runId,
                WorkoutSet(
                    sessionExerciseId = runId,
                    setNumber = 1,
                    duration = 3_600,
                    isCompleted = true,
                    completedAt = 1,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )

            val estimate = useCase()(sessionId, performed(sessionId), at = 5_000)

            val expected = CalorieEstimate.ENDURANCE_MET * CalorieEstimate.REFERENCE_BODY_MASS_MG / 1_000_000
            assertEquals(expected.roundToInt(), estimate)
        }

    @Test
    fun `a session with no exercises has no estimate`() =
        runTest {
            assertNull(useCase()(sessionId = 1L, sets = emptyList(), at = 5_000))
        }
}
