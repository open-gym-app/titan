package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.PersonalRecordDao
import com.opengym.core.data.mapper.PersonalRecordMapper
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.repository.PersonalRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The records, over [PersonalRecordDao].
 *
 * Which record is the headline one is decided in SQL, not here — see
 * [PersonalRecordDao.getHeadlineRecords]. This class only turns the rows into domain models
 * and keys them, so the ranking lives next to the index that serves it rather than being a
 * sort in Kotlin over rows SQLite has already ordered.
 */
class PersonalRecordRepositoryImpl @Inject constructor(
    private val personalRecordDao: PersonalRecordDao,
    private val personalRecordMapper: PersonalRecordMapper,
) : PersonalRecordRepository {

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> =
        personalRecordDao.getHeadlineRecords().map { rows ->
            // `mapNotNull`, because a row whose type this build cannot read is dropped rather
            // than guessed at — see the mapper. The query already guarantees one row per
            // exercise, so `associateBy` cannot lose one to a collision.
            rows.mapNotNull(personalRecordMapper::toDomainOrNull).associateBy { it.exerciseId }
        }
}
