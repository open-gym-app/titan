package com.titan.core.domain.usecase

import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.WorkoutSet

/**
 * Turning a plan into the `workout_set` rows that hold it, which is one shape written from three
 * places.
 *
 * A planned set is laid down as a real row with `is_completed = 0` — what PERSISTENCE.md calls a
 * planned-but-unperformed set: it is in the table, under its number, and it counts towards no
 * figure until the lifter ticks it off. [StartPlannedSessionUseCase] writes a template's whole
 * plan this way as the session starts, [AddSessionExercisesUseCase] writes a draft's plan the
 * same way part-way through one, and [AddPlannedSetsUseCase] writes a plan under an exercise
 * that is already there. Shared rather than written three times because the three have to
 * agree exactly: the placeholder in `completed_at`, the flag, and the timestamps are one
 * contract, and a row written differently by one path would be read differently by every
 * aggregate.
 *
 * Nothing is validated on the way down, deliberately. A planned set is allowed to be empty —
 * "do a fourth set, weight to be decided" is a plan — and `SetValidator` only holds a set to its
 * exercise's logging mode once it is *completed*, which is a rule the tick already enforces.
 */
internal object PlannedSets {

    /**
     * What `workout_set.completed_at` holds for a set nobody has performed.
     *
     * The column is NOT NULL — see PERSISTENCE.md — so a row that has not happened yet needs a
     * value that reads as "no time at all", and 0 is the one the contract names. Every aggregate
     * filters on `is_completed` before it looks at the timestamp, so the placeholder never
     * reaches one.
     */
    const val UNPERFORMED = 0L

    /**
     * [planned] as rows under [sessionExerciseId], numbered from [from] in the order planned.
     *
     * @param from the number the first row takes. [SetNumbering.FIRST] for an exercise written a
     *   moment ago, which has no sets of its own yet; a caller laying a plan into a table that
     *   already has rows passes the number the plan starts at, and owns the renumbering around
     *   it — see [AddPlannedSetsUseCase].
     * @param createdAt stamped as both `createdAt` and `updatedAt`. `completedAt` is the
     *   [UNPERFORMED] placeholder rather than this timestamp, and the difference is the point:
     *   the other two say when the row was written, which is a fact, and this one would be
     *   claiming the set was performed at the moment it was planned.
     */
    fun rows(
        planned: List<PlannedSet>,
        sessionExerciseId: Long,
        from: Int,
        createdAt: Long,
    ): List<WorkoutSet> =
        planned.mapIndexed { index, set ->
            WorkoutSet(
                sessionExerciseId = sessionExerciseId,
                setNumber = from + index,
                reps = set.reps,
                weight = set.weight,
                setType = set.setType,
                completedAt = UNPERFORMED,
                isCompleted = false,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
}

/**
 * This draft's plan as the rows that hold it, numbered from 1.
 *
 * Numbering from 1 is trivially correct for the two callers — the exercise was written a moment
 * ago and has no sets of its own yet.
 */
internal fun SessionExerciseDraft.plannedSets(
    sessionExerciseId: Long,
    createdAt: Long,
): List<WorkoutSet> =
    PlannedSets.rows(
        planned = sets,
        sessionExerciseId = sessionExerciseId,
        from = SetNumbering.FIRST,
        createdAt = createdAt,
    )
