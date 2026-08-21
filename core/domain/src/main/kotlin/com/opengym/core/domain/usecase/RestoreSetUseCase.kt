package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
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
 * second write fails, which is a table that has lost a set and says so. Survivable, and said:
 * the failure names itself [SessionError.SetTableNotRenumbered] so a caller can tell it from the
 * one where nothing was written at all.
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
     *   ended up with. A failure to renumber after the insert has landed comes back as
     *   [SessionError.SetTableNotRenumbered], carrying the row as it sits at the end of the
     *   table — the set is back, and a caller must not offer to log it again.
     */
    suspend operator fun invoke(sessionExerciseId: Long, set: WorkoutSet): Result<WorkoutSet> {
        val current = orderedSetsOf(sessionRepository, sessionStateRepository, sessionExerciseId)
            .getOrElse { return Result.failure(it) }
        val index = (set.setNumber - SetNumbering.FIRST).coerceIn(0, current.size)
        return reinsert(sessionExerciseId, set, into = current, at = index)
    }

    /**
     * The two writes, in the order that makes the failure between them survivable.
     *
     * Split off [invoke] so the checks above it and the writes below it are two readable
     * halves rather than one function of four exits.
     */
    private suspend fun reinsert(
        sessionExerciseId: Long,
        set: WorkoutSet,
        into: List<WorkoutSet>,
        at: Int,
    ): Result<WorkoutSet> {
        val now = clock.nowMillis()
        val restored = runCatching {
            val appended = set.copy(
                id = 0,
                sessionExerciseId = sessionExerciseId,
                setNumber = SetNumbering.FIRST + into.size,
                updatedAt = now,
            )
            appended.copy(id = sessionRepository.insertSet(appended))
        }.getOrElse { return Result.failure(it) }

        // Only from the slot it goes into: everything above it kept its number, and the
        // ordinary undo — the set that was deleted last is the one put back — moves nothing
        // at all, because the set is already at the end.
        //
        // A failure here is reported as the half-done thing it is rather than as a restore that
        // did not happen: the set is back, and a caller told "nothing was written" would have
        // the user log it again and end up with it twice.
        val renumbered = SetNumbering.renumber(
            sets = listOf(restored) + into.drop(at),
            now = now,
            from = SetNumbering.FIRST + at,
        )
        return sessionRepository.applyRenumbering(renumbered, restored)
            .map { restored.copy(setNumber = SetNumbering.FIRST + at) }
    }
}
