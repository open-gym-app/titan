package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock

/**
 * Removes one logged set and closes the gap it leaves.
 *
 * ### Why the remaining sets are renumbered
 *
 * `WorkoutSet.setNumber` is read, not just stored: it is the `3` in "set 3 of 4", it is what the
 * table under an exercise is ordered and labelled by, and it is what a previous-performance
 * lookup (#101) lines two sessions up on. Left alone, deleting the second of four sets leaves
 * `1, 3, 4` — a table that says the user did a fourth set they did not do, above one that
 * appears to be missing. So the numbers close up, in one atomic write; see
 * [SetNumbering] for the rule and `SessionRepository.updateSets` for why it is one write.
 *
 * Deleting the *last* set renumbers nothing, which is both the common case and the cheap one.
 *
 * ### Why there is no confirmation, and what there is instead
 *
 * Deleting a set is a frequent, small action performed mid-workout, and a dialog in front of a
 * frequent action is a dialog people dismiss without reading — which makes it worse than no
 * dialog at all, because it also trains the reflex. The answer is to make the action reversible:
 * this returns the set exactly as it stood, and [RestoreSetUseCase] puts it back where it was.
 * A caller offers that as an undo and holds the value for as long as the offer is up.
 *
 * ### What it does not do
 *
 * It does not renumber across exercises, and it does not touch the session row. It also does not
 * refuse the last set of a session — a workout with nothing logged is a legal state to be in
 * halfway through, and it is `FinishSessionUseCase` that has an opinion about finishing one.
 */
class DeleteSetUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    /**
     * @param sessionExerciseId the `session_exercise` row the set is logged against, checked
     *   against the running session's own exercises rather than trusted.
     * @param setId the set to remove, checked against that exercise's own sets.
     *
     * @return the set as it stood, carrying the `setNumber` it had before the table closed up.
     *   That is what makes the deletion reversible, and it is why this returns the row rather
     *   than the id: nothing else holds a deleted set once it is gone.
     */
    suspend operator fun invoke(sessionExerciseId: Long, setId: Long): Result<WorkoutSet> {
        val sets = orderedSetsOf(sessionRepository, sessionStateRepository, sessionExerciseId)
            .getOrElse { return Result.failure(it) }
        val deleted = sets.firstOrNull { it.id == setId }
            ?: return Result.failure(SessionError.SetNotInExercise(setId, sessionExerciseId))

        return runCatching {
            sessionRepository.deleteSet(setId)
            // After the delete rather than before it, and not best-effort. A renumbering that
            // failed on its own would leave the table with a gap in it, which is the state this
            // exists to prevent — so it is reported as a failed deletion, over a set that has in
            // fact gone. The caller re-reads the exercise either way: the sets are a `Flow`.
            val renumbered = SetNumbering.renumber(
                sets = sets.filterNot { it.id == setId },
                now = clock.nowMillis(),
            )
            if (renumbered.isNotEmpty()) sessionRepository.updateSets(renumbered)
            deleted
        }
    }
}
