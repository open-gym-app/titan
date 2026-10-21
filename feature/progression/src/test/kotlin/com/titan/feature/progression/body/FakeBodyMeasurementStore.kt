package com.titan.feature.progression.body

import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.repository.BodyMeasurementRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The measurement history, in memory.
 *
 * It behaves the way the SQL does — **newest first**, ordered by when the measurement was
 * taken rather than by when the row was written — because that ordering is a contract the
 * chart, the history list and the "latest of each figure" readings all lean on. A fake that
 * returned insertion order would let a screen that stopped relying on it still pass.
 *
 * Ids are assigned from one, like the store's, so `NEW_ENTRY` stays impossible as a real id.
 */
class FakeBodyMeasurementStore(initial: List<BodyMeasurement> = emptyList()) : BodyMeasurementRepository {

    private val entries = MutableStateFlow(initial)
    private var nextId: Long = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    /** Every entry written through [insert] or [update], in call order. */
    val writes = mutableListOf<BodyMeasurement>()

    /** Every id passed to [delete], in call order. */
    val deletions = mutableListOf<Long>()

    /**
     * Held across a write, for a test that needs the store to still be answering.
     *
     * Null is the ordinary fake, where every write lands the moment it is called. Set it and
     * [insert] and [update] park until it is completed — which is the only way to observe the
     * window a real store opens by suspending onto its IO dispatcher, and the window the log
     * sheet is up for, since the form is closed on success only.
     */
    var gate: CompletableDeferred<Unit>? = null

    override fun getAll(): Flow<List<BodyMeasurement>> = entries.map { rows -> rows.sortedWith(NEWEST_FIRST) }

    override fun getLatest(): Flow<BodyMeasurement?> = getAll().map(List<BodyMeasurement>::firstOrNull)

    override suspend fun insert(measurement: BodyMeasurement): Long {
        gate?.await()
        writes += measurement
        val id = nextId++
        entries.update { it + measurement.copy(id = id) }
        return id
    }

    override suspend fun update(measurement: BodyMeasurement) {
        gate?.await()
        writes += measurement
        entries.update { rows -> rows.map { if (it.id == measurement.id) measurement else it } }
    }

    override suspend fun delete(id: Long) {
        deletions += id
        entries.update { rows -> rows.filterNot { it.id == id } }
    }

    private companion object {
        /**
         * `ORDER BY created_at DESC, id DESC`, the DAO's own clause.
         *
         * The id is not decoration: two entries taken in the same millisecond tie on the date,
         * and a fake that left them in insertion order would hand them back the *opposite* way
         * round from the store — which is the one arrangement a test asserting on
         * `history.first()` could pass under and the app never produce.
         */
        val NEWEST_FIRST: Comparator<BodyMeasurement> =
            compareByDescending(BodyMeasurement::createdAt).thenByDescending(BodyMeasurement::id)
    }
}
