package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.PersonalRecordDao
import com.opengym.core.data.mapper.PersonalRecordMapper
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.repository.PersonalRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
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

    /**
     * The exercise's records, newest first, which is the order the DAO returns them in and the
     * order [PersonalRecordRepository.getRecordsForExercise] promises.
     *
     * `mapNotNull` for the reason [getHeadlineRecords] gives: a row whose type this build
     * cannot read is dropped rather than guessed at. Dropping preserves the ordering, so the
     * first row of a type is still that type's best.
     */
    override fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>> =
        personalRecordDao.getByExercise(exerciseId)
            .map { rows -> rows.mapNotNull(personalRecordMapper::toDomainOrNull) }

    /**
     * Counted in SQLite over every row, including ones this build's mapper could not read: a
     * record broken on a newer version is still a record broken, and dropping it here would
     * make an achievement threshold move with the app version rather than with the user.
     */
    override suspend fun count(): Int = personalRecordDao.count()

    /**
     * Counted in SQLite, over rows this build may not be able to *read* — the same reasoning as
     * [count]: a record broken on a newer version still happened, and dropping it here would
     * make a period's tally depend on which build the user has installed.
     *
     * The day range is turned into the millisecond range `achieved_at` is stored and indexed in.
     * Both ends inclusive coming in, half-open going out, exactly as `ProgressionRepositoryImpl`
     * translates for its own DAOs; the multiplication is against UTC days, which is the
     * convention every progression aggregate buckets by — see `TrainingWeek`.
     */
    override fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally> =
        personalRecordDao.getRecordTally(
            startEpochMillis = days.start.toEpochDay() * MILLIS_PER_DAY,
            endEpochMillisExclusive = (days.endInclusive.toEpochDay() + 1) * MILLIS_PER_DAY,
        ).map { row -> RecordTally(records = row.recordCount, exercises = row.exerciseCount) }

    override suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord? {
        val id = personalRecordDao.insertIfBetter(
            exerciseId = exerciseId,
            prType = type.name,
            record = personalRecordMapper.toEntity(record),
        ) { current -> isBetter(current?.let(personalRecordMapper::toDomainOrNull)) }
        return id?.let { record.copy(id = it) }
    }
}

/** What a day is, in the milliseconds `achieved_at` is stored in. */
private const val MILLIS_PER_DAY = 86_400_000L
