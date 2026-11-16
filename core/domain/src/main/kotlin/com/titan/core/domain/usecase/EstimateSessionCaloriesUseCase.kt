package com.titan.core.domain.usecase

import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.repository.BodyMeasurementRepository
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.stats.CalorieEstimate
import kotlinx.coroutines.flow.first

/**
 * Gathers what [CalorieEstimate] needs to price a session — its exercises, their catalogue
 * entries, the user's default rest and their weight on the day — and returns the estimate.
 *
 * Reads only; [FinishSessionUseCase] decides whether the figure is written, which it is only
 * when the session has none yet.
 *
 * The body measurement history is read whole rather than through a "weight at" query: it is a
 * few hundred rows at most (see [BodyMeasurementRepository.getAll]) read once per finished
 * workout, and a query of its own would be a new method on an interface with a fake in every
 * module that tests against it.
 */
class EstimateSessionCaloriesUseCase(
    private val sessionRepository: SessionRepository,
    private val exerciseRepository: ExerciseRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val bodyMeasurementRepository: BodyMeasurementRepository,
) {

    /**
     * The estimate for session [sessionId], performed as [sets] and finished at [at] (epoch
     * milliseconds), or null when [CalorieEstimate] has none to give.
     */
    suspend operator fun invoke(
        sessionId: Long,
        sets: List<WorkoutSet>,
        at: Long,
    ): Int? {
        val exercises = sessionRepository.getExercises(sessionId).first()
        val catalogue = exerciseRepository.getByIds(exercises.map { it.exerciseId }.distinct())
            .associateBy(Exercise::id)
        val defaultRest = userPreferencesRepository.observe().first().defaultRestDuration
        val bodyMass = CalorieEstimate.bodyMassAt(bodyMeasurementRepository.getAll().first(), at)

        return CalorieEstimate.of(
            exercises = exercises,
            sets = sets,
            defaultRest = defaultRest,
            catalogue = catalogue,
            bodyMass = bodyMass,
        )
    }
}
