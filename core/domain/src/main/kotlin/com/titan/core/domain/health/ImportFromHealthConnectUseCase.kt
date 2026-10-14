package com.titan.core.domain.health

import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.model.HealthConnectException
import com.titan.core.domain.model.HealthSyncOutcome
import com.titan.core.domain.model.HealthWorkoutType
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.HealthConnectGateway
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * Brings the last [WINDOW_DAYS] days of strength workouts in from Health Connect.
 *
 * ### What is skipped
 *
 * - Anything that is not strength training: runs and walks would only pollute the lifting
 *   history, and every session this app holds is strength work, so "same start and same type"
 *   is only ever a question about strength workouts.
 * - Records this app wrote (they are already here).
 * - A workout starting within [START_TOLERANCE_MILLIS] of a session already stored — which is
 *   also how a second import skips what the first one added.
 *
 * Imported sessions carry no sets, so they count as sessions but not as volume, and award no XP.
 */
class ImportFromHealthConnectUseCase(
    private val sessions: SessionRepository,
    private val gateway: HealthConnectGateway,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) {

    suspend operator fun invoke(): Result<HealthSyncOutcome> =
        try {
            Result.success(import())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: HealthConnectException) {
            Result.failure(failure)
        }

    private suspend fun import(): HealthSyncOutcome {
        val blocked = gateway.blockedReason(toggles)
        if (blocked != null) return HealthSyncOutcome.Skipped(blocked)

        val now = clock.nowMillis()
        val since = now - WINDOW_DAYS * MILLIS_PER_DAY
        val known = sessions.getAll().first()
            .filter { !it.isInProgress }
            .map { it.createdAt }
            .toMutableList()

        var imported = 0
        var skipped = 0
        for (workout in gateway.readBetween(since, now).sortedBy { it.startMillis }) {
            val duplicate = known.any { abs(it - workout.startMillis) <= START_TOLERANCE_MILLIS }
            if (workout.type != HealthWorkoutType.STRENGTH_TRAINING || workout.isOwn || duplicate) {
                skipped++
                continue
            }
            sessions.insert(
                WorkoutSession(
                    name = WorkoutSession.normalizeName(workout.title),
                    completedAt = workout.endMillis,
                    isInProgress = false,
                    lastActiveAt = workout.endMillis,
                    notes = workout.notes,
                    createdAt = workout.startMillis,
                    updatedAt = now,
                ),
            )
            known += workout.startMillis
            imported++
        }
        return HealthSyncOutcome.Imported(imported = imported, skipped = skipped)
    }

    companion object {
        const val WINDOW_DAYS = 30L
        const val START_TOLERANCE_MILLIS = 60_000L
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}
