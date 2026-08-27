package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.BodyMeasurementDao
import com.opengym.core.data.mapper.BodyMeasurementMapper
import com.opengym.core.domain.model.BodyMeasurement
import com.opengym.core.domain.repository.BodyMeasurementRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The body measurement history, over [BodyMeasurementDao].
 *
 * Ordering is the DAO's, not this class's — both queries are `ORDER BY created_at DESC` over
 * the index that serves them, so there is no sort in Kotlin over rows SQLite has already
 * ordered.
 */
class BodyMeasurementRepositoryImpl @Inject constructor(
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val bodyMeasurementMapper: BodyMeasurementMapper,
) : BodyMeasurementRepository {

    override fun getAll(): Flow<List<BodyMeasurement>> =
        bodyMeasurementDao.getAll().map { rows -> rows.map(bodyMeasurementMapper::toDomain) }

    override fun getLatest(): Flow<BodyMeasurement?> =
        bodyMeasurementDao.getLatest().map { row -> row?.let(bodyMeasurementMapper::toDomain) }

    override suspend fun insert(measurement: BodyMeasurement): Long =
        bodyMeasurementDao.insert(bodyMeasurementMapper.toEntity(measurement))

    override suspend fun update(measurement: BodyMeasurement) {
        bodyMeasurementDao.update(bodyMeasurementMapper.toEntity(measurement))
    }

    override suspend fun delete(id: Long) {
        bodyMeasurementDao.delete(id)
    }
}
