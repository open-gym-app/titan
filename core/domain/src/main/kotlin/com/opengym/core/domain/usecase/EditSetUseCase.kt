package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.domain.validation.ValidationResult

/**
 * Corrects a set that has already been logged.
 *
 * [LogSetUseCase]'s other half. Logging is append-only, and a mistyped weight is the most common
 * thing that happens to a set: two digits transposed at the moment the bar goes back on the rack
 * is a figure that then feeds the session's volume, the exercise's history and, later, the
 * personal records built on top of both. A workout that can only be added to is one where that
 * mistake is permanent.
 *
 * ### It is the same set, with different values
 *
 * The row's identity does not move. Its id, its `sessionExerciseId`, its `setNumber` and its
 * `createdAt` are the stored ones and are not taken from the caller — an edit that could change
 * the number would be a reorder of the table dressed as a correction, and one that could change
 * the exercise would move work between movements. `completedAt` stays too: the set was performed
 * when it was performed, and a correction typed ten minutes later did not move it. What moves is
 * `updatedAt`, which is the column that exists to say a row was touched.
 *
 * ### Validation is here, and also in the screen
 *
 * For the reason [LogSetUseCase] gives, and against the same rules: what refuses a weight being
 * typed has to be what refuses the weight being written, or the two disagree about the same set.
 * The refusal carries `SetValidator`'s own wording, so a correction rejected here reads exactly
 * as the same value rejected on the way in.
 *
 * Nothing is written when the values are refused. That is the whole of "does not persist": the
 * store still holds the set as it was, and the screen still holds what the user typed.
 */
class EditSetUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    /**
     * @param sessionExerciseId the `session_exercise` row the set is logged against, checked
     *   against the running session's own exercises rather than trusted — see
     *   [SessionError.ExerciseNotInSession].
     * @param setId the set to correct, checked against that exercise's own sets for the same
     *   reason — see [SessionError.SetNotInExercise].
     * @param draft the values as the screen is holding them. Its [ActiveSet.id],
     *   [ActiveSet.isCompleted] and [ActiveSet.completedAt] are **ignored**: the row this is
     *   about is named by [setId], and whether and when it was performed is not something a
     *   correction changes.
     * @param exerciseType how the exercise is logged, so [SetValidator] also checks that the
     *   corrected set still records the dimension its mode is built around. Null checks the
     *   bounds alone, as it does everywhere else.
     *
     * @return the set as it now stands, so a caller can show what it wrote rather than
     *   re-reading the exercise to find out.
     */
    suspend operator fun invoke(
        sessionExerciseId: Long,
        setId: Long,
        draft: ActiveSet,
        exerciseType: ExerciseType? = null,
    ): Result<WorkoutSet> {
        val stored = loggedSetOf(
            sessions = sessionRepository,
            state = sessionStateRepository,
            sessionExerciseId = sessionExerciseId,
            setId = setId,
        ).getOrElse { return Result.failure(it) }

        val edited = stored.corrected(draft, now = clock.nowMillis())

        return when (val result = SetValidator.validate(edited, exerciseType)) {
            is ValidationResult.Invalid -> Result.failure(SessionError.SetRejected(result.reason))

            ValidationResult.Valid -> runCatching {
                sessionRepository.updateSet(edited)
                edited
            }
        }
    }
}

/**
 * The stored row with the typed values over it.
 *
 * Written as a copy of the *stored* set rather than as a new one built from the draft, which is
 * what keeps the identity columns out of a caller's reach: anything this does not name is
 * whatever the row already held.
 */
private fun WorkoutSet.corrected(
    draft: ActiveSet,
    now: Long,
): WorkoutSet =
    copy(
        reps = draft.reps,
        weight = draft.weight,
        duration = draft.duration,
        distance = draft.distance,
        heartRateAvg = draft.heartRateAvg,
        heartRateMax = draft.heartRateMax,
        restAfter = draft.restAfter,
        rpe = draft.rpe,
        setType = draft.setType,
        notes = draft.notes,
        updatedAt = now,
    )
