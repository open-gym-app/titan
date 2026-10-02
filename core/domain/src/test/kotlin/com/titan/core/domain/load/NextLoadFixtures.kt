package com.titan.core.domain.load

import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.LoadSuggestion
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.plate.PlateSolution
import com.titan.core.domain.plate.lb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlin.math.abs

/*
 * The fixture vocabulary for the next-load engine, in the style `OneRepMaxCalculatorTest`
 * set: weights written as a lifter says them (`kg(102.5)`, `lb(225)`), a performance written
 * as its sets, and one assertion that reads like the acceptance criterion it pins.
 */

/** A barbell lift on the default catalogue's terms: plate-loaded, so the rack has the last word. */
val BENCH_PRESS = Exercise(id = 1, name = "Bench press", equipmentType = EquipmentType.BARBELL)

/** A dumbbell lift: the step is added as it is and nothing goes through the calculator. */
val DUMBBELL_PRESS = Exercise(id = 2, name = "Dumbbell press", equipmentType = EquipmentType.DUMBBELL)

/** A selectorised stack, treated exactly as the dumbbell is. */
val SEATED_ROW = Exercise(id = 3, name = "Seated row", equipmentType = EquipmentType.MACHINE)

/** A movement logged without a weight. */
val PRESS_UP = Exercise(id = 4, name = "Press-up", equipmentType = EquipmentType.BODYWEIGHT)

/** Timed work the engine declines. */
val PLANK = Exercise(id = 5, name = "Plank", type = ExerciseType.ISOMETRIC, equipmentType = EquipmentType.BODYWEIGHT)
val TREADMILL = Exercise(
    id = 6,
    name = "Treadmill",
    type = ExerciseType.ENDURANCE,
    equipmentType = EquipmentType.MACHINE,
)

/** A fresh metric install: 20 kg bar, the standard metric rack, strength, beginner. */
val METRIC: UserPreferences = UserPreferences()

/** A fresh imperial install: 45 lb bar, the standard imperial rack, strength, beginner. */
val IMPERIAL: UserPreferences = UserPreferences(unitSystem = UnitSystem.IMPERIAL, barWeight = lb(45.0))

/**
 * One performance from its sets, written `weight to reps` in set order. A null weight is a
 * bodyweight set; a null rep count is a set logged by time.
 */
fun performance(vararg sets: Pair<Long?, Int?>): PastPerformance =
    PastPerformance(
        sets.mapIndexed { index, (weight, reps) ->
            workingSet(setNumber = index + 1, weight = weight, reps = reps)
        },
    )

/** A completed working set, with the timestamps the engine never reads zeroed. */
fun workingSet(
    setNumber: Int,
    weight: Long?,
    reps: Int?,
    setType: SetType = SetType.NORMAL,
): WorkoutSet =
    WorkoutSet(
        sessionExerciseId = 1,
        setNumber = setNumber,
        reps = reps,
        weight = weight,
        setType = setType,
        isCompleted = true,
        completedAt = 0,
        createdAt = 0,
        updatedAt = 0,
    )

/**
 * The suggestion is [reason], for [reps] at [weight] — compared within
 * [PlateSolution.WEIGHT_TOLERANCE], because an imperial rack and an imperial target are two
 * roundings of the same pounds and the engine promises the *loadable* one.
 */
fun assertSuggestion(
    actual: LoadSuggestion?,
    weight: Long?,
    reps: Int,
    reason: SuggestionReason,
) {
    assertNotNull("expected a suggestion, was null", actual)
    checkNotNull(actual)
    assertEquals(reason, actual.reason)
    assertEquals(reps, actual.reps)
    if (weight == null) {
        assertEquals(null, actual.weight)
    } else {
        val actualWeight = checkNotNull(actual.weight) { "expected $weight mg, was a bodyweight suggestion" }
        assertTrue(
            "expected $weight mg, was $actualWeight mg",
            abs(actualWeight - weight) <= PlateSolution.WEIGHT_TOLERANCE,
        )
    }
}
