package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.WorkoutSessionDao
import com.opengym.core.data.db.dao.WorkoutSetDao
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.MuscleTraining
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
 * Every method takes the period as a range of days with **both ends inclusive**, the shape a
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

    override fun hasTrainingHistory(): Flow<Boolean> = sessionDao.hasFinishedSession()

    /**
     * The muscle map, ranked by sets rather than by the volume the query orders on.
     *
     * Re-sorted here rather than in the query because the two orders answer different
     * questions and the query's is the one its other callers want — see
     * `WorkoutSetDao.getVolumeByMuscleGroup`, whose `ORDER BY total_volume DESC` a parity test
     * and a benchmark both pin. Ties break on volume and then on name, so the ranking is total:
     * two groups worked for the same number of sets are ordered by how much was moved, and two
     * that match on both keep the query's own alphabetical tie-break rather than whichever
     * order SQLite happened to return.
     *
     * The muscle group is rebuilt from the two columns the projection carries. Its `icon` is
     * not among them and is left null: the diagram resolves a group by name and the ranked list
     * by id, so a third column would be one nothing reads.
     */
    override fun getMuscleTraining(days: ClosedRange<LocalDate>): Flow<List<MuscleTraining>> =
        setDao.getVolumeByMuscleGroup(days.start.toEpochDay(), days.endInclusive.toEpochDay() + 1)
            .map { rows ->
                rows.map { row ->
                    MuscleTraining(
                        muscle = MuscleGroup(id = row.muscleGroupId, name = row.muscleGroupName),
                        sets = row.setCount,
                        volume = row.weightedVolume,
                        isPrimary = row.workedDirectly,
                    )
                }.sortedWith(
                    compareByDescending(MuscleTraining::sets)
                        .thenByDescending(MuscleTraining::volume),
                )
            }

    override fun getTrainingMillis(days: ClosedRange<LocalDate>): Flow<Long> =
        sessionDao.getTrainingMillis(days.start.toEpochDay(), days.endInclusive.toEpochDay() + 1)

    override fun getFirstTrainingDay(): Flow<LocalDate?> =
        sessionDao.getFirstTrainingDay().map { epochDay -> epochDay?.let(LocalDate::ofEpochDay) }
}
