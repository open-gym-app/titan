package com.titan.core.domain.repository

import com.titan.core.domain.model.BodyMeasurement
import kotlinx.coroutines.flow.Flow

/**
 * The body measurement history — what #M5-15's body screen plots, lists and edits.
 *
 * Timestamps are the caller's, as everywhere else in the data layer: [BodyMeasurement.createdAt]
 * is when the measurement was *taken* and is what both queries order by, so an entry logged
 * this evening for this morning's weigh-in sits where it belongs in the series rather than
 * where it was typed. Nothing here stamps a clock of its own.
 *
 * Validation is the caller's too — see
 * [com.titan.core.domain.validation.BodyMeasurementValidator], which is the only thing that
 * refuses an entry recording nothing, for the reason `SetValidator` is the only thing that
 * bounds a set.
 */
interface BodyMeasurementRepository {

    /**
     * Every entry, newest first, re-emitted as the history changes.
     *
     * Newest first because both readers want that end: the history list shows the most recent
     * entries at the top, and a chart reverses what it is handed anyway. Unpaginated — a user
     * measuring themselves weekly for a decade has five hundred rows, which is a chart's worth
     * of data rather than a list needing a `PagingSource`.
     */
    fun getAll(): Flow<List<BodyMeasurement>>

    /**
     * The most recent entry, or null when nothing has been logged.
     *
     * Its own query rather than `getAll().map { it.firstOrNull() }`: it is what pre-fills the
     * log form and what the summary tiles read, neither of which has any use for the history
     * behind it, and `LIMIT 1` is what keeps that from loading every row on the way.
     */
    fun getLatest(): Flow<BodyMeasurement?>

    /** Stores a new entry and returns its id. */
    suspend fun insert(measurement: BodyMeasurement): Long

    /**
     * Overwrites the entry with [BodyMeasurement.id], in full.
     *
     * Every field is written, so clearing a measurement the user had recorded is done by
     * passing it as null — an update that only carried the non-null fields could never undo a
     * mis-typed waist.
     */
    suspend fun update(measurement: BodyMeasurement)

    /** Removes the entry, or does nothing when no row has that id. */
    suspend fun delete(id: Long)
}
