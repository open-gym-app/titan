package com.titan.core.domain.health

import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.model.HealthConnectException
import com.titan.core.domain.model.HealthSkipReason
import com.titan.core.domain.model.HealthSyncOutcome
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.model.HealthWorkoutType
import com.titan.core.domain.repository.HealthConnectGateway
import com.titan.core.domain.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Writes one finished session to Health Connect. Run after `FinishSessionUseCase` succeeds.
 *
 * Does nothing, successfully, unless the user turned sync on, Health Connect is available and
 * both permissions are granted. A failed write is returned as a failure and must never undo or
 * delay the finish it follows.
 *
 * A session has no recorded start, so its `createdAt` stands in for one.
 */
class SyncToHealthConnectUseCase(
    private val sessions: SessionRepository,
    private val gateway: HealthConnectGateway,
    private val toggles: FeatureToggles,
) {

    suspend operator fun invoke(sessionId: Long): Result<HealthSyncOutcome> =
        try {
            Result.success(sync(sessionId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: HealthConnectException) {
            Result.failure(failure)
        }

    private suspend fun sync(sessionId: Long): HealthSyncOutcome {
        val blocked = gateway.blockedReason(toggles)
        if (blocked != null) return HealthSyncOutcome.Skipped(blocked)

        val session = sessions.getById(sessionId).first()
            ?: return HealthSyncOutcome.Skipped(HealthSkipReason.SESSION_NOT_FOUND)
        val completedAt = session.completedAt
        if (session.isInProgress || completedAt == null) {
            return HealthSyncOutcome.Skipped(HealthSkipReason.SESSION_NOT_FINISHED)
        }

        gateway.write(
            HealthWorkout(
                startMillis = session.createdAt,
                endMillis = maxOf(completedAt, session.createdAt + 1),
                type = HealthWorkoutType.STRENGTH_TRAINING,
                title = session.name,
                notes = session.notes,
                externalId = HealthWorkout.externalIdFor(session.id),
            ),
        )
        return HealthSyncOutcome.Synced
    }
}
