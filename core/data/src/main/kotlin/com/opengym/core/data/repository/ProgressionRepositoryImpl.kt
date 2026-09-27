package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.WorkoutSessionDao
import com.opengym.core.data.db.dao.WorkoutSetDao
import com.opengym.core.data.db.entity.WorkoutSetEntity
import com.opengym.core.data.mapper.WorkoutSetMapper
import com.opengym.core.domain.model.AttemptSet
import com.opengym.core.domain.model.ExerciseAttempt
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.MuscleTraining
import com.opengym.core.domain.model.PastPerformance
import com.opengym.core.domain.model.TrainedExercise
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
 * There is almost nothing to map: the DAOs return projections that are already one row per
 * week, so this mostly turns an epoch day back into a [LocalDate] and hands the result on.
 * That thinness is the contract working — see [ProgressionRepository] for why the aggregation
 * has to stay in SQL, and the DAO queries for how the weeks are cut. The one method that
 * returns rows, [getRecentPerformances], is the one that takes the set mapper.
 *
 * Every method takes the period as a range of days with **both ends inclusive**, the shape a
 * caller thinks in, and the DAOs take a half-open one, the shape a `<` comparison against
 * epoch millis wants. The `+ 1` on the end is that translation and the only arithmetic in
 * this class.
 */
class ProgressionRepositoryImpl @Inject constructor(
    private val setDao: WorkoutSetDao,
    private val sessionDao: WorkoutSessionDao,
    private val setMapper: WorkoutSetMapper,
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

    override fun countSessionsFinished(days: ClosedRange<LocalDate>): Flow<Int> =
        sessionDao.countFinishedIn(days.start.toEpochDay(), days.endInclusive.toEpochDay() + 1)

    override fun getVolume(days: ClosedRange<LocalDate>): Flow<Long> =
        setDao.getVolumeIn(days.start.toEpochDay(), days.endInclusive.toEpochDay() + 1)

    override fun getFirstTrainingDay(): Flow<LocalDate?> =
        sessionDao.getFirstTrainingDay().map { epochDay -> epochDay?.let(LocalDate::ofEpochDay) }

    /**
     * The attempts, straight from the roll-up.
     *
     * The only shaping is the top set: two nullable columns become one nullable [AttemptSet],
     * and a row carrying neither a weight nor a rep count becomes **null** rather than a pair
     * of nulls — see [AttemptSet], whose two callers both need the pair or neither of it.
     */
    override fun getExerciseAttempts(
        exerciseId: Long,
        days: ClosedRange<LocalDate>,
    ): Flow<List<ExerciseAttempt>> =
        setDao.getAttemptsByExercise(
            exerciseId = exerciseId,
            startEpochDay = days.start.toEpochDay(),
            endEpochDayExclusive = days.endInclusive.toEpochDay() + 1,
        ).map { rows ->
            rows.map { row ->
                ExerciseAttempt(
                    sessionId = row.sessionId,
                    finishedAt = row.finishedAt,
                    sets = row.setCount,
                    volume = row.totalVolume,
                    topSet = topSetOf(row.topSetWeight, row.topSetReps),
                    firstSetAt = row.firstSetAt,
                )
            }
        }

    /**
     * The rows grouped back into performances, one per slot.
     *
     * `groupBy` keeps encounter order for both keys and values, and the query orders newest slot
     * first with each slot's sets by number — so the grouping *is* the contract's ordering, with
     * no second sort here to disagree with the query's. A slot cannot come back empty (the query
     * only chooses slots with a qualifying set), which is what lets [PastPerformance]'s own
     * `require` stand without a filter in front of it.
     */
    override fun getRecentPerformances(
        exerciseId: Long,
        excludingSessionId: Long,
        limit: Int,
    ): Flow<List<PastPerformance>> =
        setDao.getRecentSetsByExercise(exerciseId = exerciseId, sessionId = excludingSessionId, limit = limit)
            .map { rows ->
                rows.groupBy(WorkoutSetEntity::sessionExerciseId)
                    .values
                    .map { slot -> PastPerformance(sets = slot.map(setMapper::toDomain)) }
            }

    override fun countExerciseSets(exerciseId: Long): Flow<Int> = setDao.countSetsByExercise(exerciseId)

    override fun getTrainedExercises(): Flow<List<TrainedExercise>> =
        setDao.getTrainedExercises().map { rows ->
            rows.map { row ->
                TrainedExercise(
                    exerciseId = row.exerciseId,
                    name = row.name,
                    sets = row.setCount,
                    lastPerformedAt = row.lastPerformedAt,
                )
            }
        }

    /** A top set worth quoting, or null when the row carries nothing to quote it by. */
    private fun topSetOf(
        weight: Long?,
        reps: Int?,
    ): AttemptSet? = if (weight == null && reps == null) null else AttemptSet(weight = weight, reps = reps)
}
