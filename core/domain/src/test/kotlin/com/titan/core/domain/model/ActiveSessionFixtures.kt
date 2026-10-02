package com.titan.core.domain.model

import com.titan.core.domain.timer.RestTimerSnapshot
import kotlin.time.Duration.Companion.seconds

/*
 * Drafts the serialization and contract tests share.
 *
 * [maximalSession] exists to be *complete* rather than realistic: every nullable field
 * is non-null and every defaulted one is off its default, so a round trip that drops a
 * field cannot pass by leaving a null where a null was expected.
 */

/** A plausible session start, fixed so the encoded documents are stable. */
const val STARTED_AT: Long = 1_700_000_000_000L

/** Every field populated and no field on its default. */
fun maximalSession(): ActiveSession =
    ActiveSession(
        sessionId = 7,
        startedAt = STARTED_AT,
        pausedDuration = 95.seconds,
        pausedAt = STARTED_AT + 600_000,
        exercises = listOf(
            // The pair is one superset, which is both what makes `supersetGroup` non-null
            // on every exercise here and the only grouping two exercises can validly have.
            ActiveExercise(
                exerciseId = 12,
                sessionExerciseId = 34,
                supersetGroup = 0,
                notes = "belt on from set 3",
                sets = listOf(maximalSet(), ActiveSet(reps = 8)),
            ),
            ActiveExercise(exerciseId = 13, sessionExerciseId = 35, supersetGroup = 0),
        ),
        currentExerciseIndex = 1,
        restTimer = RestTimerSnapshot.Running(deadline = STARTED_AT + 700_000, total = 90.seconds),
    )

/** Every field of a set populated and no field on its default. */
fun maximalSet(): ActiveSet =
    ActiveSet(
        id = 56,
        reps = 5,
        weight = 102_500_000,
        duration = 45,
        distance = 20_000,
        heartRateAvg = 132,
        heartRateMax = 168,
        restAfter = 90,
        rpe = 85,
        setType = SetType.DROP_SET,
        isCompleted = true,
        completedAt = STARTED_AT + 120_000,
        notes = "last one moved slow",
    )

/** The smallest draft the invariants allow: a session that has only just started. */
fun minimalSession(): ActiveSession = ActiveSession(sessionId = 1, startedAt = STARTED_AT)
