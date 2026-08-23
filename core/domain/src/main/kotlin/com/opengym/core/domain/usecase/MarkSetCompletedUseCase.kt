package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.domain.validation.ValidationResult

/**
 * Ticks a logged set off, or takes the tick back off it.
 *
 * ### Why un-ticking is a write of its own
 *
 * The tick is the one thing on a set that is pressed by accident, because it is the control the
 * entry row exists to reach and it sits under a thumb that is also holding the phone. Every other
 * mistake about a set is a *value* and is corrected by [EditSetUseCase]; this one is not — the
 * set was never performed — and the two are deliberately not the same call. `EditSetUseCase`
 * ignores [WorkoutSet.isCompleted] on the draft it is handed, precisely so that a correction
 * typed into four fields cannot silently unperform the set it is correcting. What is left is this:
 * one flag, named, with nothing else it can change.
 *
 * The alternative was to delete the set and let the user log it again. That is worse in both
 * directions: it throws away the reps and the weight that *were* right, and it renumbers the whole
 * table under the user for a mistake about one row — see [DeleteSetUseCase] for what a deletion
 * actually costs.
 *
 * ### An un-ticked set is a set that is still there
 *
 * `isCompleted = false` is not a soft delete and it is not a new state invented here. It is what
 * PERSISTENCE.md already calls a planned-but-unperformed set: the row stays in its exercise's
 * table, under the number it had, and it contributes to no figure — `SessionStatsCalculator`,
 * `VolumeCalculator`, `MuscleVolumeCalculator` and [FinishSessionUseCase] all filter on the flag
 * already, so nothing downstream needs to learn about this. What the user sees is the row going
 * quiet: still readable, no longer counted.
 *
 * ### What it does not touch
 *
 * [WorkoutSet.completedAt] stays exactly as it was, in both directions, **with one exception**.
 * The column records when the set was performed, and neither noticing a stray tick nor putting it
 * back is a claim about *when*: clearing it on the way out would leave the row unable to say when
 * it had been done, and re-stamping it on the way back in would move a set the user did ten
 * minutes ago to now. The flag says whether the set counts; the timestamp says when it happened.
 * Only [WorkoutSet.updatedAt] moves, which is the column that exists to say a row was touched.
 *
 * The exception is a set that has *never* been performed — one a template laid down as part of
 * the plan, whose `completed_at` is the `0` placeholder PERSISTENCE.md names for a row that has
 * not happened. Ticking that one off is not putting a timestamp back, it is the first time there
 * has been one, and leaving the placeholder would file the set at epoch 1970: the same contract
 * warns that a row with `is_completed = 1` and `completed_at = 0` is counted there by every
 * aggregate that reads the column. So a first tick stamps the clock, and every later one leaves
 * what it finds.
 *
 * The set numbers are untouched too, unlike a deletion: nothing has left the table, so there is no
 * gap to close.
 *
 * ### Ticking one back on is validated; ticking one off cannot be
 *
 * `SetValidator` only asks a *completed* set to have filled in the dimension its exercise is
 * logged in, so the two directions are asymmetric and rightly so. Un-ticking is always allowed —
 * whatever the row holds, it holds less of a claim afterwards. Re-ticking runs the same check
 * `LogSetUseCase` and `EditSetUseCase` run, so a set that lost its reps in the meantime cannot be
 * marked performed again through this door while it would be refused through theirs.
 */
class MarkSetCompletedUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    /**
     * @param sessionExerciseId the `session_exercise` row the set is logged against, checked
     *   against the running session's own exercises rather than trusted — see
     *   [SessionError.ExerciseNotInSession].
     * @param setId the set to tick, checked against that exercise's own sets for the same reason
     *   — see [SessionError.SetNotInExercise].
     * @param isCompleted what the tick should end up saying. Named rather than toggled: the
     *   caller is a control that already knows which way it is being pressed, and a toggle
     *   computed here would flip whatever the row happens to hold — which, for a double tap
     *   against a slow write, is not what the user pressed twice.
     * @param exerciseType how the exercise is logged, so a set being marked performed is held to
     *   the same completeness rule the write paths hold it to. Ignored when [isCompleted] is
     *   false, because `SetValidator` asks nothing of an unperformed set. Null checks the bounds
     *   alone, as it does everywhere else.
     *
     * @return the set as it now stands. A set that already says what was asked is returned
     *   unchanged and nothing is written — a repeated tap is not an edit, and a write per tap
     *   would move `updatedAt` for no reason anybody could read back.
     */
    suspend operator fun invoke(
        sessionExerciseId: Long,
        setId: Long,
        isCompleted: Boolean,
        exerciseType: ExerciseType? = null,
    ): Result<WorkoutSet> {
        val stored = loggedSetOf(
            sessions = sessionRepository,
            state = sessionStateRepository,
            sessionExerciseId = sessionExerciseId,
            setId = setId,
        ).getOrElse { return Result.failure(it) }

        if (stored.isCompleted == isCompleted) return Result.success(stored)

        val now = clock.nowMillis()
        val edited = stored.copy(
            isCompleted = isCompleted,
            // Only for a set that has never been performed, and only on the way in. See the
            // note on the class: this is the first timestamp the row has ever had, not a
            // replacement for one it already carries.
            completedAt = if (isCompleted && stored.completedAt == NEVER_PERFORMED) {
                now
            } else {
                stored.completedAt
            },
            updatedAt = now,
        )

        return when (val result = SetValidator.validate(edited, exerciseType)) {
            is ValidationResult.Invalid -> Result.failure(SessionError.SetRejected(result.reason))
            ValidationResult.Valid -> runCatching {
                sessionRepository.updateSet(edited)
                edited
            }
        }
    }

    private companion object {

        /**
         * The `completed_at` a set that has not been performed carries, per PERSISTENCE.md
         * and [StartPlannedSessionUseCase], which is what writes them.
         */
        const val NEVER_PERFORMED = 0L
    }
}
