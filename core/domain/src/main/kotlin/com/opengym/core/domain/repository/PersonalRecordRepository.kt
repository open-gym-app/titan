package com.opengym.core.domain.repository

import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import kotlinx.coroutines.flow.Flow

/**
 * The personal records the PR engine ([com.opengym.core.domain.usecase.PrDetectionUseCase])
 * writes.
 *
 * Writable as of #30: [insertIfBetter] is the one way in, and nothing outside the engine may
 * call it — a record authored anywhere else would be a "personal record" nobody actually
 * achieved. Before this, the interface was read-only because there was no writer to give a
 * contract to; a screen that reads an empty table is still showing a user who has not
 * beaten anything yet, which was true before the engine existed and stays true for a fresh
 * install after it.
 */
interface PersonalRecordRepository {

    /**
     * The one record worth putting on a row, per exercise, keyed by exercise id.
     *
     * A list row has space for a single figure, and an exercise accumulates records of
     * several kinds — a heaviest, a most-reps, an estimated 1RM off the same set. Choosing
     * between them is a decision the catalogue would otherwise make differently on every
     * screen that draws a row, so it is made once, in the query: the heaviest lift if there
     * is one, else the most reps, else whatever kind of best the exercise has. Ties within a
     * kind go to the most recent, which for a record of a given kind is also the best one.
     *
     * A map rather than a list because every caller looks records up by exercise, and an
     * exercise has at most one entry here by construction.
     */
    fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>>

    /**
     * Writes [record] as [exerciseId]'s new record of [type], but only if it beats what is
     * already on file — and returns it (with its id) if so, or **null** if it did not.
     *
     * "What is already on file" is the most recently achieved row of that exercise and type,
     * which is also the best one — a record of a given kind is only ever written when it
     * beats the row before it, so there is nothing to rank between rows of the same type.
     * [isBetter] is handed that row (or **null**, while none has been set yet) and decides;
     * this repository does not re-derive "better" from the type, so that comparison stays in
     * one place, [com.opengym.core.domain.usecase.PrDetectionUseCase].
     *
     * The read of the current best and the write are one atomic operation — [isBetter] is
     * evaluated inside the same transaction the insert happens in, so two concurrent calls
     * for the same exercise and type cannot both read the same "current" row before either
     * writes: the second call's transaction only starts once the first has committed, and so
     * sees the first call's write. A caller that split this into a separate read-then-insert
     * would reopen exactly that race.
     *
     * Always an insert, never an update, when it does write: a broken record does not replace
     * the old one, it joins it, so an exercise's full history of records stays queryable
     * rather than only its current best.
     */
    suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord?
}
