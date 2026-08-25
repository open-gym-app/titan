package com.opengym.core.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first

/**
 * Shape of the history [WorkoutHistoryFixture] seeds: [sessionCount] sessions,
 * each with [exercisesPerSession] exercises drawn from a catalogue of
 * [exerciseCount], each with [setsPerExercise] completed sets - so the fixture
 * writes [setCount] `workout_set` rows in total.
 */
data class HistorySpec(
    val sessionCount: Int,
    val exercisesPerSession: Int,
    val setsPerExercise: Int,
    val exerciseCount: Int = DEFAULT_EXERCISE_COUNT,
    val muscleGroupsPerExercise: Int = DEFAULT_MUSCLE_GROUPS_PER_EXERCISE,
    /** One record per session, so `personal_record` grows with history too. */
    val withPersonalRecords: Boolean = true,
    /** An extra, most-recently-active in-progress session for `getActive`. */
    val withActiveSession: Boolean = true,
) {
    init {
        // Positions within a session are unique, and an exercise is not repeated
        // inside one session, so the catalogue has to be at least a session wide.
        require(exerciseCount >= exercisesPerSession) {
            "exerciseCount ($exerciseCount) must cover exercisesPerSession ($exercisesPerSession)"
        }
    }

    val sessionExerciseCount: Int get() = sessionCount * exercisesPerSession

    val setCount: Int get() = sessionExerciseCount * setsPerExercise

    companion object {
        const val DEFAULT_EXERCISE_COUNT = 20
        const val DEFAULT_MUSCLE_GROUPS_PER_EXERCISE = 3
    }
}

/** Ids written by [WorkoutHistoryFixture.seed], in insertion order. */
data class SeededHistory(
    val spec: HistorySpec,
    val muscleGroupIds: List<Long>,
    val exerciseIds: List<Long>,
    /** Completed sessions only, oldest first; the in-progress one is [activeSessionId]. */
    val sessionIds: List<Long>,
    val activeSessionId: Long?,
)

/**
 * Builds a workout history through the DAO layer - the same insert path the app
 * uses, so foreign keys, indexes and defaults all apply - for tests that need
 * volume rather than a hand-written fixture: the cross-DAO integration test, the
 * performance guards, and anything else that needs a populated database.
 *
 * Every generated value is a pure function of the row's position in the history
 * ([sessionCreatedAt], [setCompletedAt], [repsFor], [weightFor], [exerciseSlotOf]),
 * so a test can assert what it reads back without the fixture having to hand it a
 * copy of everything it inserted. Values vary between sets on purpose: a
 * round-trip that mixed up two sets, or dropped a column, changes them.
 */
class WorkoutHistoryFixture(private val db: OpenGymDatabase) {

    suspend fun seed(spec: HistorySpec): SeededHistory =
        db.withTransaction {
            val muscleGroupIds = seedMuscleGroups()
            val exerciseIds = seedExercises(spec, muscleGroupIds)
            val sessionIds = (0 until spec.sessionCount).map { sessionIndex ->
                seedSession(spec, sessionIndex, exerciseIds)
            }
            SeededHistory(
                spec = spec,
                muscleGroupIds = muscleGroupIds,
                exerciseIds = exerciseIds,
                sessionIds = sessionIds,
                activeSessionId = if (spec.withActiveSession) seedActiveSession(spec) else null,
            )
        }

    private suspend fun seedMuscleGroups(): List<Long> {
        db.muscleGroupDao().insertAll(MUSCLE_GROUPS.map { muscleGroup(it) })
        return db.muscleGroupDao().getAll().first().map { it.id }
    }

    /**
     * Links every exercise to one primary and [HistorySpec.muscleGroupsPerExercise] - 1
     * secondary muscle groups, so the muscle-group aggregate has a realistic
     * fan-out instead of an empty join.
     */
    private suspend fun seedExercises(
        spec: HistorySpec,
        muscleGroupIds: List<Long>,
    ): List<Long> =
        (0 until spec.exerciseCount).map { exerciseIndex ->
            val exerciseId = db.exerciseDao().insert(exercise(name = "Exercise $exerciseIndex"))
            repeat(spec.muscleGroupsPerExercise) { slot ->
                db.exerciseDao().addMuscleGroup(
                    exerciseMuscleGroup(
                        exerciseId = exerciseId,
                        muscleGroupId = muscleGroupIds[(exerciseIndex + slot) % muscleGroupIds.size],
                        isPrimary = slot == 0,
                    ),
                )
            }
            exerciseId
        }

    private suspend fun seedSession(
        spec: HistorySpec,
        sessionIndex: Int,
        exerciseIds: List<Long>,
    ): Long {
        val createdAt = sessionCreatedAt(sessionIndex)
        val sessionId = db.workoutSessionDao().insert(
            session(isInProgress = false, lastActiveAt = createdAt, createdAt = createdAt),
        )
        (0 until spec.exercisesPerSession).forEach { exerciseSlot ->
            val exerciseId = exerciseIds[exerciseSlotOf(sessionIndex, exerciseSlot, exerciseIds.size)]
            val sessionExerciseId = db.sessionExerciseDao().insert(
                sessionExercise(sessionId, exerciseId, position = exerciseSlot),
            )
            seedSets(spec, sessionIndex, exerciseSlot, exerciseId, sessionExerciseId)
        }
        return sessionId
    }

    private suspend fun seedSets(
        spec: HistorySpec,
        sessionIndex: Int,
        exerciseSlot: Int,
        exerciseId: Long,
        sessionExerciseId: Long,
    ) {
        (1..spec.setsPerExercise).forEach { setNumber ->
            val setId = db.workoutSetDao().insert(
                workoutSet(
                    sessionExerciseId = sessionExerciseId,
                    setNumber = setNumber,
                    isCompleted = true,
                    completedAt = setCompletedAt(spec, sessionIndex, exerciseSlot, setNumber),
                    reps = repsFor(setNumber),
                    weight = weightFor(exerciseSlot, setNumber),
                ),
            )
            // The opening set of each session also sets a record.
            if (spec.withPersonalRecords && exerciseSlot == 0 && setNumber == 1) {
                db.personalRecordDao().insert(
                    personalRecord(
                        exerciseId = exerciseId,
                        workoutSetId = setId,
                        achievedAt = sessionCreatedAt(sessionIndex),
                    ),
                )
            }
        }
    }

    /** An in-progress session more recent than all the seeded history. */
    private suspend fun seedActiveSession(spec: HistorySpec): Long {
        val startedAt = sessionCreatedAt(spec.sessionCount)
        return db.workoutSessionDao().insert(
            session(isInProgress = true, lastActiveAt = startedAt, createdAt = startedAt),
        )
    }

    companion object {
        /** A representative slice of the seeded taxonomy - see `DatabaseSeed`. */
        val MUSCLE_GROUPS =
            listOf("CHEST", "LATS", "SHOULDERS", "BICEPS", "TRICEPS", "QUADRICEPS", "ABDOMINALS")

        private const val MILLIS_PER_DAY = 86_400_000L

        /** One session per day, so 1,000 sessions is a bit under three years of history. */
        private const val SESSION_INTERVAL_MILLIS = MILLIS_PER_DAY

        /** Ninety seconds between sets, i.e. a session is a contiguous block of work. */
        private const val SET_INTERVAL_MILLIS = 90_000L

        /** Sessions start mid-morning, so a bucket that ignored the time of day would show. */
        private const val SESSION_START_MILLIS = 10 * 3_600_000L

        private const val MILLIGRAMS_PER_KILOGRAM = 1_000_000L

        /** Epoch millis at which session [sessionIndex] (0-based, oldest first) started. */
        fun sessionCreatedAt(sessionIndex: Int): Long = sessionIndex * SESSION_INTERVAL_MILLIS + SESSION_START_MILLIS

        /** Epoch day of session [sessionIndex], for the day-range analytics queries. */
        fun sessionEpochDay(sessionIndex: Int): Long = sessionCreatedAt(sessionIndex) / MILLIS_PER_DAY

        /** Index into the exercise catalogue used at [exerciseSlot] of a session. */
        fun exerciseSlotOf(
            sessionIndex: Int,
            exerciseSlot: Int,
            exerciseCount: Int,
        ): Int = (sessionIndex + exerciseSlot) % exerciseCount

        fun setCompletedAt(
            spec: HistorySpec,
            sessionIndex: Int,
            exerciseSlot: Int,
            setNumber: Int,
        ): Long =
            sessionCreatedAt(sessionIndex) +
                (exerciseSlot * spec.setsPerExercise + setNumber - 1) * SET_INTERVAL_MILLIS

        /** Reps taper as a set gets heavier, and never drop below one. */
        fun repsFor(setNumber: Int): Int = maxOf(1, REPS_ON_FIRST_SET - setNumber + 1)

        /** Weight in milligrams - the canonical storage unit. */
        fun weightFor(
            exerciseSlot: Int,
            setNumber: Int,
        ): Long =
            (BASE_WEIGHT_KG + exerciseSlot * WEIGHT_STEP_PER_SLOT_KG + setNumber * WEIGHT_STEP_PER_SET_KG) *
                MILLIGRAMS_PER_KILOGRAM

        private const val REPS_ON_FIRST_SET = 12
        private const val BASE_WEIGHT_KG = 40L
        private const val WEIGHT_STEP_PER_SLOT_KG = 5L
        private const val WEIGHT_STEP_PER_SET_KG = 2L
    }
}
