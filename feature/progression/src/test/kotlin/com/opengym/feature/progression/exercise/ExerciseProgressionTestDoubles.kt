package com.opengym.feature.progression.exercise

import com.opengym.core.domain.model.AttemptSet
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseAttempt
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.FitnessLevel
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.MuscleTraining
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.model.Tag
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainedExercise
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.model.WeeklyTraining
import com.opengym.core.domain.model.WeeklyVolume
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration

/**
 * The stores behind the per-exercise drill-down, in memory.
 *
 * Separate from the hub's doubles in `StatsTestDoubles.kt` rather than shared with them: the
 * two screens read different halves of the same interfaces, and a fake that answered both
 * would let a test pass while the screen under it quietly started reading something it has no
 * business reading. Each stubs what its own screen does not take, loudly.
 *
 * The attempt store behaves the way the SQL does — rows outside the requested range are not
 * returned, and what comes back is ascending by session — because the ordering is a contract
 * the charts and the table both lean on.
 */
class FakeExerciseProgressionStore : ProgressionRepository {

    private val attempts = MutableStateFlow<Map<Long, List<ExerciseAttempt>>>(emptyMap())
    private val setCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private val trained = MutableStateFlow<List<TrainedExercise>>(emptyList())
    private val firstDay = MutableStateFlow<LocalDate?>(null)

    /** Every range an attempt read was taken over, in call order. */
    val requestedRanges = mutableListOf<ClosedRange<LocalDate>>()

    /** Every exercise an attempt read was taken for, in call order. */
    val requestedExercises = mutableListOf<Long>()

    /** How long an attempt read takes to answer. Zero — the default — answers at once. */
    var readLatencyMillis: Long = 0

    fun setAttempts(
        exerciseId: Long,
        vararg performed: ExerciseAttempt,
    ) {
        attempts.value += exerciseId to performed.toList()
    }

    fun setSetCount(
        exerciseId: Long,
        count: Int,
    ) {
        setCounts.value += exerciseId to count
    }

    fun setTrained(vararg exercises: TrainedExercise) {
        trained.value = exercises.toList()
    }

    /** The day the user's history opens on, which only an all-time window reads. */
    fun setFirstTrainingDay(day: LocalDate?) {
        firstDay.value = day
    }

    override fun getExerciseAttempts(
        exerciseId: Long,
        days: ClosedRange<LocalDate>,
    ): Flow<List<ExerciseAttempt>> {
        requestedRanges += days
        requestedExercises += exerciseId
        return attempts
            .map { byExercise ->
                byExercise[exerciseId]
                    .orEmpty()
                    .filter { LocalDate.ofEpochDay(it.finishedAt / MILLIS_PER_DAY) in days }
                    .sortedBy(ExerciseAttempt::finishedAt)
            }
            .onStart { if (readLatencyMillis > 0) delay(readLatencyMillis) }
    }

    override fun countExerciseSets(exerciseId: Long): Flow<Int> = setCounts.map { counts -> counts[exerciseId] ?: 0 }

    override fun getTrainedExercises(): Flow<List<TrainedExercise>> = trained

    override fun getFirstTrainingDay(): Flow<LocalDate?> = firstDay

    /*
     * The hub's five reads. This screen takes none of them, so they fail loudly rather than
     * answering with an empty list a drifting ViewModel could quietly start relying on.
     */

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> = unsupported()

    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> = unsupported()

    override fun hasTrainingHistory(): Flow<Boolean> = unsupported()

    override fun getMuscleTraining(days: ClosedRange<LocalDate>): Flow<List<MuscleTraining>> = unsupported()

    override fun getTrainingMillis(days: ClosedRange<LocalDate>): Flow<Long> = unsupported()

    override fun countSessionsFinished(days: ClosedRange<LocalDate>): Flow<Int> = unsupported()

    override fun getVolume(days: ClosedRange<LocalDate>): Flow<Long> = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the drill-down reads one exercise, not the whole period")
}

/** The records table, as the rows one exercise has. */
class FakeExerciseRecordStore : PersonalRecordRepository {

    private val records = MutableStateFlow<Map<Long, List<PersonalRecord>>>(emptyMap())

    /** Seeds one exercise's records. Stored as given; the read sorts them newest first. */
    fun setRecords(
        exerciseId: Long,
        vararg written: PersonalRecord,
    ) {
        records.value += exerciseId to written.toList()
    }

    /**
     * Newest first, which is the ordering the interface promises and the ordering the use case
     * takes "the best of this kind" off — a fake that returned insertion order would let a
     * caller that stopped relying on it still pass.
     */
    override fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>> =
        records.map { byExercise ->
            byExercise[exerciseId].orEmpty().sortedByDescending(PersonalRecord::achievedAt)
        }

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = unsupported()

    override suspend fun count(): Int = unsupported()

    override fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally> = unsupported()

    override suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord? = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the drill-down only reads one exercise's records")

    /** Unused here: this fake serves the per-exercise reads, not the records list. */
    override suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry> = emptyList()

    override fun observeRecordCount(): Flow<Int> = flowOf(0)
}

/** The catalogue, as the rows this screen looks a name up in. */
class FakeExerciseCatalogue(private val exercises: List<Exercise>) : ExerciseRepository {

    override fun getById(id: Long): Flow<Exercise?> = MutableStateFlow(exercises.firstOrNull { it.id == id })

    override fun getAll(): Flow<List<Exercise>> = unsupported()

    override fun getByType(type: ExerciseType): Flow<List<Exercise>> = unsupported()

    override fun getByEquipmentType(equipmentType: EquipmentType): Flow<List<Exercise>> = unsupported()

    override fun searchByPartialName(query: String): Flow<List<Exercise>> = unsupported()

    override fun getMuscleGroupLinks(exerciseId: Long): Flow<List<ExerciseMuscleGroupLink>> = unsupported()

    override fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>> = unsupported()

    override fun getTags(exerciseId: Long): Flow<List<Tag>> = unsupported()

    override fun getMuscleGroups(): Flow<List<MuscleGroup>> = unsupported()

    override suspend fun insert(exercise: Exercise): Long = unsupported()

    override suspend fun insert(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ): Long = unsupported()

    override suspend fun update(exercise: Exercise) = unsupported()

    override suspend fun update(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ) = unsupported()

    override suspend fun delete(id: Long) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the drill-down only looks one exercise up by id")
}

/**
 * The settings store, for the one field this screen reads: which formula projects a one-rep
 * max. The rest is present to satisfy the interface.
 */
class FakeSettings(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {

    private val preferences = MutableStateFlow(initial)

    override fun observe(): Flow<UserPreferences> = preferences

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) = write { it.copy(oneRepMaxFormula = formula) }

    override suspend fun setUnitSystem(unitSystem: UnitSystem) = write { it.copy(unitSystem = unitSystem) }

    override suspend fun setTheme(theme: ThemePreference) = write { it.copy(theme = theme) }

    override suspend fun setDefaultRestDuration(duration: Duration) = write { it.copy(defaultRestDuration = duration) }

    override suspend fun setBarWeight(barWeight: Long) = write { it.copy(barWeight = barWeight) }

    override suspend fun setPlateInventory(inventory: PlateInventory?) = write { it.copy(plateInventory = inventory) }

    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) = write { it.copy(plateInventory = it.plates.withPairs(weight, pairs)) }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) = write { it.copy(trainingGoal = trainingGoal) }

    override suspend fun setFitnessLevel(fitnessLevel: FitnessLevel) = write { it.copy(fitnessLevel = fitnessLevel) }

    override suspend fun setOnboardingCompleted(completed: Boolean) = write { it.copy(onboardingCompleted = completed) }

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = write { it.copy(notifications = it.notifications.with(kind, enabled)) }

    override suspend fun setNotificationPermissionRequested() =
        write { it.copy(notifications = it.notifications.copy(permissionRequested = true)) }

    override suspend fun setReminderDay(
        day: DayOfWeek,
        enabled: Boolean,
    ) = write {
        val days = it.reminders.days
        it.copy(reminders = it.reminders.copy(days = if (enabled) days + day else days - day))
    }

    override suspend fun setReminderTime(time: LocalTime) =
        write { it.copy(reminders = it.reminders.copy(timeOfDay = time)) }

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) = preferences.update(transform)
}

// ─── Fixtures ───────────────────────────────────────────────────────────────────────────

const val BENCH_ID = 1L
const val SQUAT_ID = 2L

val BENCH = Exercise(
    id = BENCH_ID,
    name = "Bench Press",
    type = ExerciseType.STRENGTH,
    equipmentType = EquipmentType.BARBELL,
)

val SQUAT = Exercise(id = SQUAT_ID, name = "Back Squat", type = ExerciseType.STRENGTH)

/** Kilograms in the canonical storage unit, milligrams. */
fun kg(value: Long): Long = value * 1_000_000L

/** One attempt on [day], closed out at midday UTC. */
fun attemptOn(
    day: LocalDate,
    sessionId: Long,
    sets: Int = 4,
    volume: Long = kg(1_200),
    weight: Long? = kg(80),
    reps: Int? = 5,
): ExerciseAttempt {
    val finishedAt = day.toEpochDay() * MILLIS_PER_DAY + 12 * 3_600_000L
    return ExerciseAttempt(
        sessionId = sessionId,
        finishedAt = finishedAt,
        sets = sets,
        volume = volume,
        topSet = if (weight == null && reps == null) null else AttemptSet(weight = weight, reps = reps),
        firstSetAt = finishedAt - 3_600_000L,
    )
}

/** One record of [type], achieved at [achievedAt]. */
fun recordOf(
    type: PrType,
    achievedAt: Long,
    exerciseId: Long = BENCH_ID,
    weight: Long? = null,
    estimated1Rm: Long? = null,
) = PersonalRecord(
    id = achievedAt,
    exerciseId = exerciseId,
    workoutSetId = achievedAt,
    type = type,
    weight = weight,
    estimated1Rm = estimated1Rm,
    achievedAt = achievedAt,
)

/** What a day is, in the milliseconds a session's close-out is stored in. */
const val MILLIS_PER_DAY = 86_400_000L
