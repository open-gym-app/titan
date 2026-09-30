package com.titan.feature.history.detail

import com.titan.core.domain.model.DifficultyLevel
import com.titan.core.domain.model.Discipline
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseAttempt
import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.LoadSuggestion
import com.titan.core.domain.model.MechanicType
import com.titan.core.domain.model.MuscleTraining
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.TrainedExercise
import com.titan.core.domain.model.WeeklyTraining
import com.titan.core.domain.model.WeeklyVolume
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.repository.ProgressionRepository
import com.titan.feature.history.library.CHEST
import com.titan.feature.history.library.LATS
import com.titan.feature.history.library.TRICEPS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/*
 * Two exercises: one with everything the seed supplies, one with everything a user can.
 *
 * They are the two shapes the detail screen has to survive — see the issue's acceptance
 * criteria — and keeping them here rather than in each test class is what stops the "sparse"
 * case quietly acquiring an instruction or a photo when somebody extends a fixture.
 */

/** The seeded shape: a photo, a how-to, every metadata column, and a note already written. */
val DETAIL_BENCH = Exercise(
    id = 10,
    name = "Bench press",
    discipline = Discipline.POWERLIFTING,
    equipmentType = EquipmentType.BARBELL,
    mechanicType = MechanicType.COMPOUND,
    difficultyLevel = DifficultyLevel.INTERMEDIATE,
    notes = "Seat on 3.",
    instructions = "Unrack the bar.\nLower it to the chest.\nPress back to lockout.",
    icon = "seed/images/Barbell_Bench_Press.webp",
)

/** The authored shape: a name, some kit, one muscle, and nothing else at all. */
val DETAIL_CUSTOM = Exercise(
    id = 11,
    name = "Hammer press",
    equipmentType = EquipmentType.MACHINE,
    isCustom = true,
)

val DETAIL_BENCH_LINKS = listOf(
    ExerciseMuscleGroupLink(CHEST, isPrimary = true),
    ExerciseMuscleGroupLink(TRICEPS, isPrimary = false),
)

val DETAIL_CUSTOM_LINKS = listOf(ExerciseMuscleGroupLink(LATS, isPrimary = true))

/** The state the screen draws for a fully-populated seeded exercise. */
fun benchState() =
    ExerciseDetailUiState(
        exercise = DETAIL_BENCH,
        muscleLinks = DETAIL_BENCH_LINKS,
        isLoading = false,
    )

/** The state the screen draws for a user-authored one: every optional section absent. */
fun customState() =
    ExerciseDetailUiState(
        exercise = DETAIL_CUSTOM,
        muscleLinks = DETAIL_CUSTOM_LINKS,
        isLoading = false,
    )

/**
 * Two bests on the bench: the heaviest single, and the most reps.
 *
 * One per kind, which is what the screen is handed — see `bestByType`. Two kinds rather than one
 * so a layout that drew only the first row would fail rather than look right.
 */
fun detailRecords() =
    listOf(
        PersonalRecord(
            id = 1,
            exerciseId = DETAIL_BENCH.id,
            workoutSetId = 1,
            type = PrType.MAX_WEIGHT,
            weight = 92_000_000,
            achievedAt = RECORD_ACHIEVED_AT,
        ),
        PersonalRecord(
            id = 2,
            exerciseId = DETAIL_BENCH.id,
            workoutSetId = 2,
            type = PrType.MAX_REPS,
            reps = 12,
            achievedAt = RECORD_ACHIEVED_AT,
        ),
    )

/** 2026-04-21, so the rows read as a date rather than as the epoch. */
private const val RECORD_ACHIEVED_AT = 1_776_729_600_000L

/**
 * What the engine makes of three sets of 80 × 6 under the strength range: the top was hit, so
 * the rack's step goes on and the reps drop back. The shape the card is handed.
 */
fun benchTarget() =
    LoadSuggestion(
        weight = 82_500_000,
        reps = 3,
        reason = SuggestionReason.INCREASE,
        previousWeight = 80_000_000,
        basis = benchedLastTime(),
    )

/** Last time's bench: three working sets of 80 × 6, the history [benchTarget] is read from. */
fun benchedLastTime() =
    PastPerformance(List(3) { index -> workingSet(setNumber = index + 1, weight = 80_000_000, reps = 6) })

fun workingSet(
    setNumber: Int,
    weight: Long?,
    reps: Int,
) = WorkoutSet(
    sessionExerciseId = PREVIOUS_ROW,
    setNumber = setNumber,
    reps = reps,
    weight = weight,
    isCompleted = true,
    completedAt = RECORD_ACHIEVED_AT,
    createdAt = RECORD_ACHIEVED_AT,
    updatedAt = RECORD_ACHIEVED_AT,
)

/** A `session_exercise` row of some earlier workout, which the detail screen never resolves. */
private const val PREVIOUS_ROW = 99L

/**
 * The history the next-load engine reads (#M7-16), in memory: what a test seeded, newest
 * first, and a record of what was asked for. The query's own rules are asserted against a
 * real database in `RecentPerformanceDaoTest`; what matters here is the session the read was
 * told to leave out.
 */
class FakeProgressionStore : ProgressionRepository {

    private val performances = MutableStateFlow<Map<Long, List<PastPerformance>>>(emptyMap())

    /** Every (exercise, excluded session) the history has been asked for, in call order. */
    val reads = mutableListOf<Pair<Long, Long?>>()

    fun setPerformances(
        exerciseId: Long,
        vararg newestFirst: PastPerformance,
    ) {
        performances.value += exerciseId to newestFirst.toList()
    }

    override fun getRecentPerformances(
        exerciseId: Long,
        excludingSessionId: Long?,
        limit: Int,
    ): Flow<List<PastPerformance>> {
        reads += exerciseId to excludingSessionId
        return performances.map { byExercise -> byExercise[exerciseId].orEmpty().take(limit) }
    }

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> = unsupported()
    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> = unsupported()
    override fun hasTrainingHistory(): Flow<Boolean> = unsupported()
    override fun getMuscleTraining(days: ClosedRange<LocalDate>): Flow<List<MuscleTraining>> = unsupported()
    override fun getTrainingMillis(days: ClosedRange<LocalDate>): Flow<Long> = unsupported()
    override fun countSessionsFinished(days: ClosedRange<LocalDate>): Flow<Int> = unsupported()
    override fun getVolume(days: ClosedRange<LocalDate>): Flow<Long> = unsupported()
    override fun getFirstTrainingDay(): Flow<LocalDate?> = unsupported()
    override fun getExerciseAttempts(
        exerciseId: Long,
        days: ClosedRange<LocalDate>,
    ): Flow<List<ExerciseAttempt>> = unsupported()

    override fun countExerciseSets(exerciseId: Long): Flow<Int> = unsupported()
    override fun getTrainedExercises(): Flow<List<TrainedExercise>> = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the exercise detail reads recent performances only")
}
