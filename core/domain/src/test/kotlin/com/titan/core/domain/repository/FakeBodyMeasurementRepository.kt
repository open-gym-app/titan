package com.titan.core.domain.repository

import com.titan.core.domain.model.BodyMeasurement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [BodyMeasurementRepository] for domain tests: the history as a list, handed back
 * newest first as the contract says.
 *
 * [failing] makes every read throw, for the callers whose contract is to survive a store that
 * cannot be read.
 */
class FakeBodyMeasurementRepository(
    initial: List<BodyMeasurement> = emptyList(),
    private val failing: Boolean = false,
) : BodyMeasurementRepository {

    private val entries = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull(BodyMeasurement::id) ?: 0L) + 1

    override fun getAll(): Flow<List<BodyMeasurement>> =
        entries.map { rows ->
            check(!failing) { "the body measurement store cannot be read" }
            rows.sortedByDescending(BodyMeasurement::createdAt)
        }

    override fun getLatest(): Flow<BodyMeasurement?> = getAll().map(List<BodyMeasurement>::firstOrNull)

    override suspend fun insert(measurement: BodyMeasurement): Long {
        val id = nextId++
        entries.value += measurement.copy(id = id)
        return id
    }

    override suspend fun update(measurement: BodyMeasurement) {
        entries.value = entries.value.map { if (it.id == measurement.id) measurement else it }
    }

    override suspend fun delete(id: Long) {
        entries.value = entries.value.filterNot { it.id == id }
    }
}
