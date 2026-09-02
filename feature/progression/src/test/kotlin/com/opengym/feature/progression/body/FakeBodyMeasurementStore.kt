package com.opengym.feature.progression.body

import com.opengym.core.domain.model.BodyMeasurement
import com.opengym.core.domain.repository.BodyMeasurementRepository
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

    override fun getAll(): Flow<List<BodyMeasurement>> =
        entries.map { rows -> rows.sortedByDescending(BodyMeasurement::createdAt) }

    override fun getLatest(): Flow<BodyMeasurement?> = getAll().map(List<BodyMeasurement>::firstOrNull)

    override suspend fun insert(measurement: BodyMeasurement): Long {
        writes += measurement
        val id = nextId++
        entries.update { it + measurement.copy(id = id) }
        return id
    }

    override suspend fun update(measurement: BodyMeasurement) {
        writes += measurement
        entries.update { rows -> rows.map { if (it.id == measurement.id) measurement else it } }
    }

    override suspend fun delete(id: Long) {
        deletions += id
        entries.update { rows -> rows.filterNot { it.id == id } }
    }
}
