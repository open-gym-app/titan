package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository

/**
 * Keeping one exercise's set numbers contiguous, which is a rule about the *table* rather than
 * about any set in it.
 *
 * `WorkoutSet.setNumber` is what a card reads as "set 3 of 4". Nothing in the schema keeps it
 * dense — see `SessionRepository.deleteSet` — so a gap left by a deletion would be read as a set
 * that exists and is missing, and the previous-performance lookup (#101) would line last
 * session's fourth set up against this session's third. Deleting and restoring are therefore
 * both renumberings, and they are the same renumbering: put the sets in the order they were
 * performed, and number them from 1.
 *
 * Shared between [DeleteSetUseCase] and [RestoreSetUseCase] rather than written twice, because
 * the two have to agree exactly — an undo that numbered differently from the delete it reverses
 * would leave the table in a state neither of them produced.
 */
internal object SetNumbering {

    /** The number the first set of an exercise carries. Set numbers are read, so they start at 1. */
    const val FIRST = 1

    /**
     * [sets] renumbered from [FIRST] in the order given, keeping only the rows that moved.
     *
     * Only the rows that moved, for the reason `SessionRepository.updateSets` documents: a set
     * the renumbering left where it was should keep the `updatedAt` it had, and re-emitting
     * every observer of a session per deletion is a cost with no reader's benefit. Deleting the
     * *last* set of an exercise therefore writes nothing at all, which is the common case.
     *
     * @param sets the exercise's sets **in the order they are performed**. Callers hand over a
     *   list sorted by the numbers being replaced; this does not sort, because the one caller
     *   that inserts a set into the middle of the table has already put it where it belongs and
     *   sorting by the old numbers would put it back.
     * @param now stamped as `updatedAt` on the rows that moved. The domain's clocks live in the
     *   use cases, which is what makes them testable without one.
     * @param from the number the first of [sets] takes. Defaulted to [FIRST], which is what a
     *   whole table is numbered from; a caller renumbering only the tail of one passes the
     *   number the tail starts at.
     */
    fun renumber(sets: List<WorkoutSet>, now: Long, from: Int = FIRST): List<WorkoutSet> =
        sets.mapIndexedNotNull { index, set ->
            val number = from + index
            set.copy(setNumber = number, updatedAt = now).takeIf { set.setNumber != number }
        }

    /** One exercise's sets in the order they were performed, which is what the numbers record. */
    fun inOrder(sets: List<WorkoutSet>): List<WorkoutSet> = sets.sortedBy(WorkoutSet::setNumber)
}

/**
 * Writes a renumbering back, reporting a failure as the half-done thing it is.
 *
 * The second of the two writes [DeleteSetUseCase] and [RestoreSetUseCase] each make, and the
 * one that can fail after the first has already landed — the set is out of the workout, or back
 * in it, and only the numbers around it are wrong. Shared for the same reason [SetNumbering] is:
 * the two have to report that state identically, because a caller tells them apart by nothing
 * but which one it called.
 *
 * @param renumbered the rows that moved, which is empty for the common case — the last set of an
 *   exercise, deleted or put back — and writes nothing at all.
 * @param written the row the first write already landed, carried on the refusal so a caller can
 *   still act over the set that has genuinely moved. See [SessionError.SetTableNotRenumbered].
 */
internal suspend fun SessionRepository.applyRenumbering(
    renumbered: List<WorkoutSet>,
    written: WorkoutSet,
): Result<Unit> = when {
    renumbered.isEmpty() -> Result.success(Unit)
    else -> runCatching { updateSets(renumbered) }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { Result.failure(SessionError.SetTableNotRenumbered(written, it)) },
    )
}
