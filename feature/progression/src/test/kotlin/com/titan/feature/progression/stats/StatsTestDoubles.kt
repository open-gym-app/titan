package com.titan.feature.progression.stats

import com.titan.core.domain.model.ExerciseAttempt
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.MuscleTraining
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PersonalRecordEntry
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.RecordTally
import com.titan.core.domain.model.TrainedExercise
import com.titan.core.domain.model.WeeklyTraining
import com.titan.core.domain.model.WeeklyVolume
import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.ProgressionRepository
import com.titan.core.domain.stats.TrainingWeek
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The stores behind the stats hub, in memory.
 *
 * The two series behave the way the SQL ones do rather than the way a test would find
 * convenient — a week with nothing in it has **no row**, and rows outside the requested range
 * are not returned — because the gap filling is the use cases' job and a fake that pre-filled
 * would prove nothing about the screen built on them. The same rules `:core:domain`'s own fake
 * documents at length.
 *
 * [requestedRanges] is what a test asserts the shared-period rule with: every read has to be
 * taken over the *same* window, or the hub is drawing a muscle map of thirty days beside a
 * chart of five weeks.
 *
 * [readLatencyMillis] is the one thing here a real store has and an in-memory one does not: a
 * `1Y` window over a season of training does not answer in the same frame it was asked in, and
 * a screen whose behaviour *while it waits* is the thing under test cannot be built on a fake
 * that never makes it wait.
 */
class FakeProgressionStore : ProgressionRepository {

    private val volume = MutableStateFlow<List<WeeklyVolume>>(emptyList())
    private val training = MutableStateFlow<List<WeeklyTraining>>(emptyList())
    private val everTrained = MutableStateFlow(false)
    private val muscles = MutableStateFlow<List<MuscleTraining>>(emptyList())
    private val millis = MutableStateFlow(0L)
    private val firstDay = MutableStateFlow<LocalDate?>(null)

    /** Every range this store has been read over, in call order. */
    val requestedRanges = mutableListOf<ClosedRange<LocalDate>>()

    /** How long every windowed read takes to answer. Zero — the default — answers at once. */
    var readLatencyMillis: Long = 0

    /** Seeds a week's volume, snapped to the Monday that opens it. */
    fun setVolume(vararg weeks: Pair<LocalDate, Long>) {
        volume.value = weeks
            .map { (day, total) -> WeeklyVolume(TrainingWeek.startOf(day), total) }
            .sortedBy(WeeklyVolume::weekStart)
        if (weeks.isNotEmpty()) markTrained(weeks.first().first)
    }

    /** Seeds a week's training days and sessions, snapped as [setVolume] snaps. */
    fun setTraining(vararg weeks: Triple<LocalDate, Int, Int>) {
        training.value = weeks
            .map { (day, days, sessions) -> WeeklyTraining(TrainingWeek.startOf(day), days, sessions) }
            .sortedBy(WeeklyTraining::weekStart)
        if (weeks.isNotEmpty()) markTrained(weeks.first().first)
    }

    fun setMuscles(vararg trained: MuscleTraining) {
        muscles.value = trained.toList()
    }

    fun setTrainingMillis(value: Long) {
        millis.value = value
    }

    /**
     * The lapsed user: training that is real but falls outside every window the screen asks
     * for, so it cannot be seeded as a week.
     */
    fun setHasTrainingHistory(
        value: Boolean,
        since: LocalDate? = null,
    ) {
        everTrained.value = value
        firstDay.value = since
    }

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> {
        requestedRanges += days
        return volume.map { rows -> rows.filter { it.weekStart in days } }.delayed()
    }

    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> {
        requestedRanges += days
        return training.map { rows -> rows.filter { it.weekStart in days } }.delayed()
    }

    override fun hasTrainingHistory(): Flow<Boolean> = everTrained

    override fun getMuscleTraining(days: ClosedRange<LocalDate>): Flow<List<MuscleTraining>> {
        requestedRanges += days
        return muscles.delayed()
    }

    override fun getTrainingMillis(days: ClosedRange<LocalDate>): Flow<Long> {
        requestedRanges += days
        return millis.delayed()
    }

    /*
     * The two scalar period reads, which the stats hub does not use — they exist for the quest
     * board (#105). Zero rather than `unsupported()`: this double is handed to a whole screen,
     * and a read nothing on it makes should not be able to fail a test by being wired up later.
     */

    override fun countSessionsFinished(days: ClosedRange<LocalDate>): Flow<Int> = flowOf(0)

    override fun getVolume(days: ClosedRange<LocalDate>): Flow<Long> = flowOf(0L)

    override fun getFirstTrainingDay(): Flow<LocalDate?> = firstDay

    /*
     * The drill-down's three reads. The hub does not take them — it is the exercise screen
     * (#102) that does, against its own doubles — so they fail loudly here rather than
     * returning an empty list that would let a hub quietly start reading them.
     */

    override fun getExerciseAttempts(
        exerciseId: Long,
        days: ClosedRange<LocalDate>,
    ): Flow<List<ExerciseAttempt>> = throw UnsupportedOperationException("the hub reads no single exercise")

    override fun countExerciseSets(exerciseId: Long): Flow<Int> =
        throw UnsupportedOperationException("the hub reads no single exercise")

    override fun getTrainedExercises(): Flow<List<TrainedExercise>> =
        throw UnsupportedOperationException("the hub reads no single exercise")

    override fun getRecentPerformances(
        exerciseId: Long,
        excludingSessionId: Long?,
        limit: Int,
    ): Flow<List<PastPerformance>> = throw UnsupportedOperationException("the hub reads no single exercise")

    private fun markTrained(day: LocalDate) {
        everTrained.value = true
        if (firstDay.value == null) firstDay.value = TrainingWeek.startOf(day)
    }

    /**
     * This read, held open for [readLatencyMillis] before its first row.
     *
     * On `onStart` rather than on every emission, which is where the wait actually is: a query
     * is slow to answer and then re-emits as fast as the table changes under it.
     */
    private fun <T> Flow<T>.delayed(): Flow<T> = onStart { if (readLatencyMillis > 0) delay(readLatencyMillis) }
}

/** The records table, as a tally the test writes directly. */
class FakeRecordStore(private var tally: RecordTally = RecordTally(records = 0, exercises = 0)) :
    PersonalRecordRepository {

    /** Every range the tally was read over, in call order. */
    val requestedRanges = mutableListOf<ClosedRange<LocalDate>>()

    /** Seeds what the window's records add up to. */
    fun setTally(value: RecordTally) {
        tally = value
    }

    override fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally> {
        requestedRanges += days
        return MutableStateFlow(tally)
    }

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = MutableStateFlow(emptyMap())

    override suspend fun count(): Int = throw UnsupportedOperationException("no test here counts records")

    override fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>> =
        throw UnsupportedOperationException("the hub reads no single exercise's records")

    override suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord? = throw UnsupportedOperationException("no test here writes a personal record")

    /** Unused here: this fake serves the per-exercise reads, not the records list. */
    override suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry> = emptyList()

    override fun observeRecordCount(): Flow<Int> = flowOf(0)
}

/** A clock stopped at a known instant, so "the last 90 days" is a range a test can write down. */
class FixedClock(private val nowMillis: Long) : Clock {
    override fun nowMillis(): Long = nowMillis
}

/** Midday on [date] in the device's zone — the zone the screen derives "today" in. */
fun middayOn(date: LocalDate): Long =
    date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** One muscle group's training over the window. */
fun trained(
    id: Long,
    name: String,
    sets: Int,
    isPrimary: Boolean = true,
) = MuscleTraining(
    muscle = MuscleGroup(id = id, name = name),
    sets = sets,
    volume = sets * 1_000_000L,
    isPrimary = isPrimary,
)
