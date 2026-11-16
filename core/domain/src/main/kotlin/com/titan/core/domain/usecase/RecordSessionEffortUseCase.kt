package com.titan.core.domain.usecase

import com.titan.core.domain.model.SessionError
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.validation.SessionEffortValidator
import com.titan.core.domain.validation.ValidationResult

/**
 * Sets — or clears — what a workout burned and how the user rates it.
 *
 * Written from the recap, which is both the screen a workout ends on and the screen a past one is
 * reopened on, so like [RenameSessionUseCase] it makes no state check: a rating given to last
 * Tuesday's session is as much the point as one given a minute after finishing.
 *
 * Both figures are written together because they are edited together, on one card; either may be
 * null, and null clears it. A figure written here replaces the estimate [FinishSessionUseCase]
 * left, and clearing it does not bring the estimate back: an empty field is the user saying the
 * workout was not measured. They are bounded by [SessionEffortValidator] first, and a refusal is
 * [SessionError.EffortRejected] with nothing written.
 *
 * Only a figure that changes is bounded. The recap hands back the stored one alongside the one
 * being edited, and a restore writes rows without validating them, so checking both would let an
 * out-of-bounds calorie count from a backup refuse every star the user tapped afterwards.
 *
 * `updatedAt` is left where it is, for the reason [RenameSessionUseCase] gives: neither figure is
 * a change to what was performed.
 */
class RecordSessionEffortUseCase(private val sessionRepository: SessionRepository) {

    /**
     * Fails with [SessionError.SessionNotFound] for an id that no longer resolves, and with
     * [SessionError.EffortRejected] for a figure out of bounds.
     */
    suspend operator fun invoke(
        sessionId: Long,
        calories: Int?,
        rating: Int?,
    ): Result<Unit> {
        val session = sessionRepository.loadSession(sessionId)
            .getOrElse { return Result.failure(it) }

        val verdict = SessionEffortValidator.validate(
            calories = calories.takeUnless { it == session.calories },
            rating = rating.takeUnless { it == session.rating },
        )
        if (verdict is ValidationResult.Invalid) return Result.failure(SessionError.EffortRejected(verdict.reason))
        if (session.calories == calories && session.rating == rating) return Result.success(Unit)

        return runCatching { sessionRepository.update(session.copy(calories = calories, rating = rating)) }
    }
}
