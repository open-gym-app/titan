package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.WorkoutSessionDao
import com.opengym.core.data.db.dao.WorkoutSetDao
import com.opengym.core.domain.model.WeeklyTraining
import com.opengym.core.domain.model.WeeklyVolume
import com.opengym.core.domain.repository.ProgressionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

/**
 * The progression aggregates, over the two DAOs that own the rows being rolled up.
 *
 * There is no mapper here and nothing to map: the DAOs return projections that are already
 * one row per week, so this only turns an epoch day back into a [LocalDate] and hands the
 * result on. That thinness is the contract working — see [ProgressionRepository] for why the
 * aggregation has to stay in SQL, and the DAO queries for how the weeks are cut.
 *
 * Both methods take the period as a range of days with **both ends inclusive**, the shape a
 * caller thinks in, and the DAOs take a half-open one, the shape a `<` comparison against
 * epoch millis wants. The `+ 1` on the end is that translation and the only arithmetic in
 * this class.
 */
class ProgressionRepositoryImpl @Inject constructor(
    private val setDao: WorkoutSetDao,
    private val sessionDao: WorkoutSessionDao,
) : ProgressionRepository {

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> =
        setDao.getVolumeByWeek(days.start.toEpochDay(), days.endInclusive.toEpochDay() + 1)
            .map { rows ->
                rows.map { row ->
                    WeeklyVolume(
                        weekStart = LocalDate.ofEpochDay(row.weekStartEpochDay),
                        totalVolume = row.totalVolume,
                    )
                }
            }

    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> =
        sessionDao.getTrainingByWeek(days.start.toEpochDay(), days.endInclusive.toEpochDay() + 1)
            .map { rows ->
                rows.map { row ->
                    WeeklyTraining(
                        weekStart = LocalDate.ofEpochDay(row.weekStartEpochDay),
                        trainingDays = row.trainingDays,
                        sessionCount = row.sessionCount,
                    )
                }
            }
}
