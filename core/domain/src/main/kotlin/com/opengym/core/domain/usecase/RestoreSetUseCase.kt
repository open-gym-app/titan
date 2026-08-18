package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock

/**
 * Puts a deleted set back, where it was.
 *
 * The undo behind [DeleteSetUseCase], and the reason that one needs no confirmation dialog. It
 * takes the set the deletion handed back and writes it again with its values, its type, its
 * rating, its note and — the part that makes it an undo rather than a re-entry — the
 * `setNumber` it had. Restoring the second of four sets puts it back between the first and the
 * third, and the sets that closed up over it move down again.
 *
 * ### It is a new row
 *
 * The id is not restored. `workout_set.id` is assigned by the store and a deleted one is gone;
 * inventing a row at a chosen id would be a write path that exists for one undo and that no
 * other caller could use safely. Nothing reads a set's id across a deletion — the screens key on
 * it only for the life of a list — so what "intact" means here is every value the user entered,
 * and its place in the table.
 *
 * ### Why it inserts at the end and then renumbers
 *
 * The two writes cannot be one: the row has to exist before it can be numbered against the rows
 * around it. Doing them in this order is what makes a failure between them survivable — the set
 * is back, at the bottom of its exercise's table, which is a workout with the user's work in it.
 * The other order (shift the table up, then insert) leaves a gap where the set should be if the
 * second write fails, which is a table that has lost a set and says so.
 */
class RestoreSetUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    /**
     * @param sessionExerciseId the `session_exercise` row the set belongs to, checked against
     *   the running session's own exercises rather than trusted. An undo offered over a workout
     *   that has since been finished or replaced is refused here rather than writing a set into
     *   it.
     * @param set the deleted set as [DeleteSetUseCase] handed it back. Its `setNumber` is where
     *   it goes; a number past the end of the table as it now stands puts it at the end, which
     *   is what happens when sets were logged in the time the undo was on offer.
     *
     * @return the set as it was written, carrying the id the store assigned and the number it
     *   ended up with.
     */
    suspend operator fun invoke(sessionExerciseId: Long, set: WorkoutSet): Result<WorkoutSet> {
        val current = orderedSetsOf(sessionRepository, sessionStateRepository, sessionExerciseId)
            .getOrElse { return Result.failure(it) }
        val index = (set.setNumber - SetNumbering.FIRST).coerceIn(0, current.size)
        val now = clock.nowMillis()

        return runCatching {
            val appended = set.copy(
                id = 0,
                sessionExerciseId = sessionExerciseId,
                setNumber = SetNumbering.FIRST + current.size,
                updatedAt = now,
            )
            val restored = appended.copy(id = sessionRepository.insertSet(appended))

            // Only from the slot it goes into: everything above it kept its number, and the
            // ordinary undo — the set that was deleted last is the one put back — moves nothing
            // at all, because the set is already at the end.
            val renumbered = SetNumbering.renumber(
                sets = listOf(restored) + current.drop(index),
                now = now,
                from = SetNumbering.FIRST + index,
            )
            if (renumbered.isNotEmpty()) sessionRepository.updateSets(renumbered)

            restored.copy(setNumber = SetNumbering.FIRST + index)
        }
    }
}
