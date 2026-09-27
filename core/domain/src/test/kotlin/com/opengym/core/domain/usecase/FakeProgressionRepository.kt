package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ExerciseAttempt
import com.opengym.core.domain.model.MuscleTraining
import com.opengym.core.domain.model.PastPerformance
import com.opengym.core.domain.model.TrainedExercise
import com.opengym.core.domain.model.WeeklyTraining
import com.opengym.core.domain.model.WeeklyVolume
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.stats.TrainingWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * The weekly aggregates, in memory, built to behave the way the SQL ones do rather than the
 * way a test would find convenient.
 *
 * Three properties of the real queries are reproduced here on purpose, because a use case
 * that only worked against a friendlier fake would be a use case that does not work:
 *
 *  - **A week with nothing in it has no row.** `GROUP BY` produces rows for what exists, so
 *    filling the gaps is the use case's job and a fake that pre-filled them would prove
 *    nothing about it.
 *  - **Rows outside the requested range are not returned.** The fake holds weekly totals, so
 *    it can only filter on the bucket's Monday; the real queries filter row by row and can
 *    hand back a *partial* boundary bucket. Rather than let that difference pass unnoticed,
 *    a range that is not whole weeks is **rejected** — see [requireWholeWeeks]. A use case
 *    that stopped snapping its period would fail here instead of quietly agreeing with a
 *    fake that cannot produce the bucket it would then be wrong about.
 *  - **Rows come back ascending by week**, since both queries are `ORDER BY week_start ASC`.
 *
 * [requestedRanges] records what was asked for, which is how a test asserts that the
 * comparison period is fetched by widening one query rather than by making a second.
 */
class FakeProgressionRepository : ProgressionRepository {

    private val volume = MutableStateFlow<List<WeeklyVolume>>(emptyList())
    private val training = MutableStateFlow<List<WeeklyTraining>>(emptyList())
    private val everTrained = MutableStateFlow(false)
    private val muscleTraining = MutableStateFlow<List<MuscleTraining>>(emptyList())
    private val trainingMillis = MutableStateFlow(0L)
    private val firstTrainingDay = MutableStateFlow<LocalDate?>(null)
    private val attempts = MutableStateFlow<Map<Long, List<ExerciseAttempt>>>(emptyMap())
    private val setCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private val performances = MutableStateFlow<Map<Long, List<PastPerformance>>>(emptyMap())
    private val trainedExercises = MutableStateFlow<List<TrainedExercise>>(emptyList())
    private val sessionsByDay = MutableStateFlow<Map<LocalDate, Int>>(emptyMap())
    private val volumeByDay = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())

    /** Every range this repository has been read over, in call order. */
    val requestedRanges = mutableListOf<ClosedRange<LocalDate>>()

    /** Every depth [getRecentPerformances] has been asked for, in call order. */
    val requestedLimits = mutableListOf<Int>()

    /**
     * Seeds one week's volume. [weekStart] is snapped to its Monday, so a test can write a
     * date that reads like a training day and still land in the bucket the query would put
     * it in. Seeding anything also makes [hasTrainingHistory] true, since a seeded week is
     * history; [setHasTrainingHistory] is for the case that has none to seed.
     */
    fun setVolume(vararg weeks: Pair<LocalDate, Long>) {
        volume.value = weeks
            .map { (day, total) -> WeeklyVolume(TrainingWeek.startOf(day), total) }
            .sortedBy(WeeklyVolume::weekStart)
        if (weeks.isNotEmpty()) everTrained.value = true
    }

    /** Seeds one week's training days and sessions, snapped as [setVolume] snaps. */
    fun setTraining(vararg weeks: Triple<LocalDate, Int, Int>) {
        training.value = weeks
            .map { (day, days, sessions) -> WeeklyTraining(TrainingWeek.startOf(day), days, sessions) }
            .sortedBy(WeeklyTraining::weekStart)
        if (weeks.isNotEmpty()) everTrained.value = true
    }

    /** Seeds the muscle map, in the order the query would return it. */
    fun setMuscleTraining(vararg muscles: MuscleTraining) {
        muscleTraining.value = muscles.toList()
    }

    /** Seeds how long the period's sessions ran for, in milliseconds. */
    fun setTrainingMillis(millis: Long) {
        trainingMillis.value = millis
    }

    /** Seeds the day the user first finished a session, or null for one who never has. */
    fun setFirstTrainingDay(day: LocalDate?) {
        firstTrainingDay.value = day
    }

    /**
     * Sets the all-history predicate on its own — the lapsed user, whose training is real but
     * falls outside every range the use case asks for and so cannot be seeded as a week.
     */
    fun setHasTrainingHistory(value: Boolean) {
        everTrained.value = value
    }

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> {
        requestedRanges += requireWholeWeeks(days)
        return volume.map { rows -> rows.filter { it.weekStart in days } }
    }

    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> {
        requestedRanges += requireWholeWeeks(days)
        return training.map { rows -> rows.filter { it.weekStart in days } }
    }

    override fun hasTrainingHistory(): Flow<Boolean> = everTrained

    /*
     * The two scalar period reads, held **per day** rather than per week.
     *
     * Deliberately not routed through [requireWholeWeeks], unlike everything above: these are
     * the reads whose whole point is that they are correct over a range that is not a whole
     * number of weeks — a calendar month, for the quest board. Modelling them by day is what
     * lets a test put a session on the 31st and assert it lands in that month and not the next,
     * which is the boundary the real queries get right by filtering row by row.
     */

    override fun countSessionsFinished(days: ClosedRange<LocalDate>): Flow<Int> {
        requestedRanges += days
        return sessionsByDay.map { byDay -> byDay.filterKeys { it in days }.values.sum() }
    }

    override fun getVolume(days: ClosedRange<LocalDate>): Flow<Long> {
        requestedRanges += days
        return volumeByDay.map { byDay -> byDay.filterKeys { it in days }.values.sum() }
    }

    /** Seeds [count] finished sessions on [day]. Additive across calls for the same day. */
    fun setSessionsOn(
        day: LocalDate,
        count: Int,
    ) {
        sessionsByDay.value = sessionsByDay.value + (day to (sessionsByDay.value[day] ?: 0) + count)
    }

    /** Seeds [milligramReps] of volume on [day]. Additive across calls for the same day. */
    fun setVolumeOn(
        day: LocalDate,
        milligramReps: Long,
    ) {
        volumeByDay.value = volumeByDay.value + (day to (volumeByDay.value[day] ?: 0L) + milligramReps)
    }

    /*
     * The three period readings, which nothing in this module derives anything from — they are
     * read straight by a screen, so there is no gap-filling or bucketing for a fake to model.
     * Plain seedable values, and still routed through [requireWholeWeeks] so a caller that
     * stopped snapping its period fails here as it does above.
     */

    override fun getMuscleTraining(days: ClosedRange<LocalDate>): Flow<List<MuscleTraining>> {
        requestedRanges += requireWholeWeeks(days)
        return muscleTraining
    }

    override fun getTrainingMillis(days: ClosedRange<LocalDate>): Flow<Long> {
        requestedRanges += requireWholeWeeks(days)
        return trainingMillis
    }

    override fun getFirstTrainingDay(): Flow<LocalDate?> = firstTrainingDay

    /** Seeds one exercise's attempts. Stored as given; the reads sort and filter. */
    fun setAttempts(
        exerciseId: Long,
        vararg performed: ExerciseAttempt,
    ) {
        attempts.value += exerciseId to performed.toList()
    }

    /** Seeds the all-time working-set count for one exercise. */
    fun setExerciseSetCount(
        exerciseId: Long,
        count: Int,
    ) {
        setCounts.value += exerciseId to count
    }

    /** Seeds the chooser's list, in the order the query would return it. */
    fun setTrainedExercises(vararg exercises: TrainedExercise) {
        trainedExercises.value = exercises.toList()
    }

    /**
     * The attempts in [days], oldest first.
     *
     * **Not** routed through [requireWholeWeeks], unlike everything above it: an attempt is a
     * session on a date rather than a weekly bucket, so the real query answers any range
     * exactly and there is no partial bucket for a fake to be wrong about. Placed by the UTC
     * day the session was closed out, which is how the query places it.
     */
    override fun getExerciseAttempts(
        exerciseId: Long,
        days: ClosedRange<LocalDate>,
    ): Flow<List<ExerciseAttempt>> {
        requestedRanges += days
        return attempts.map { byExercise ->
            byExercise[exerciseId]
                .orEmpty()
                .filter { LocalDate.ofEpochDay(it.finishedAt / MILLIS_PER_DAY) in days }
                .sortedBy(ExerciseAttempt::finishedAt)
        }
    }

    override fun countExerciseSets(exerciseId: Long): Flow<Int> = setCounts.map { counts -> counts[exerciseId] ?: 0 }

    /**
     * Seeds one exercise's recent performances, **newest first** — the order the query returns
     * and the order [getRecentPerformances] hands on, truncated to the limit it is asked for.
     * Nothing here filters by session: the real query excludes the session being logged, and a
     * test that wants that case seeds the history without it.
     */
    fun setPerformances(
        exerciseId: Long,
        vararg newestFirst: PastPerformance,
    ) {
        performances.value += exerciseId to newestFirst.toList()
    }

    override fun getRecentPerformances(
        exerciseId: Long,
        excludingSessionId: Long,
        limit: Int,
    ): Flow<List<PastPerformance>> {
        requestedLimits += limit
        return performances.map { byExercise -> byExercise[exerciseId].orEmpty().take(limit) }
    }

    override fun getTrainedExercises(): Flow<List<TrainedExercise>> = trainedExercises

    /**
     * Rejects a range that starts anywhere but a Monday or ends anywhere but a Sunday.
     *
     * The real queries answer such a range with a partial boundary bucket; this holds only
     * whole weeks and would answer with the whole one or with nothing, either of which is a
     * wrong number a test would read as right. Refusing is the honest option, and it turns
     * "the caller forgot to snap" into a failure rather than a coincidence.
     */
    private fun requireWholeWeeks(days: ClosedRange<LocalDate>): ClosedRange<LocalDate> {
        require(TrainingWeek.startOf(days.start) == days.start) {
            "range must start on a Monday, was ${days.start}"
        }
        val dayAfter = days.endInclusive.plusDays(1)
        require(TrainingWeek.startOf(dayAfter) == dayAfter) {
            "range must end on a Sunday, was ${days.endInclusive}"
        }
        return days
    }
}

/** What a day is, in the milliseconds a session's `completedAt` is stored in. */
private const val MILLIS_PER_DAY = 86_400_000L
