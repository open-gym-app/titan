package com.opengym.core.domain.repository

import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import kotlinx.coroutines.flow.Flow

/**
 * The personal records the PR engine ([com.opengym.core.domain.usecase.PrDetectionUseCase])
 * writes.
 *
 * Writable as of #30: [insert] is the one way in, and nothing outside the engine may call
 * it — a record authored anywhere else would be a "personal record" nobody actually
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
     * The current best of [type] for [exerciseId], or **null** while none has been set.
     *
     * "Current" is the most recently achieved row of that exercise and type, which is also
     * the best one — a record of a given kind is only ever written when it beats the row
     * before it, per [insert]'s contract, so there is nothing to rank between candidates of
     * the same type.
     *
     * What [com.opengym.core.domain.usecase.PrDetectionUseCase] reads before deciding
     * whether a just-logged set beats what is already on file.
     */
    fun getBest(exerciseId: Long, type: PrType): Flow<PersonalRecord?>

    /**
     * Writes [record] as a new personal record and returns its id.
     *
     * Always an insert, never an update: a broken record does not replace the old one, it
     * joins it, so an exercise's full history of records stays queryable rather than only
     * its current best.
     *
     * Nothing here checks that [record] is actually an improvement — that judgement is
     * [com.opengym.core.domain.usecase.PrDetectionUseCase]'s, made against [getBest] before
     * this is ever called. A repository that re-derived "better" from the type would be a
     * second copy of a comparison that already lives in one place.
     */
    suspend fun insert(record: PersonalRecord): Long
}
