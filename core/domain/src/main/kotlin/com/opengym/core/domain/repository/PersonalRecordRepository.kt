package com.opengym.core.domain.repository

import com.opengym.core.domain.model.PersonalRecord
import kotlinx.coroutines.flow.Flow

/**
 * The personal records the PR engine (#30) writes.
 *
 * Read-only for now, and deliberately so: nothing outside the engine may declare a record,
 * and until the engine exists there is no writer to give a contract to. A screen that reads
 * an empty table is showing a user who has not beaten anything yet, which is the same thing
 * the screens will show on a fresh install after the engine lands.
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
}
