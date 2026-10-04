package com.titan.feature.session.active

import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.validation.SetValidator
import com.titan.core.domain.validation.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether an entry fills in the dimension its exercise is logged in (#M7-17), asked of every
 * mode and every combination of fields — and held against `SetValidator` itself, so the tick and
 * the write path cannot drift apart.
 *
 * The fields are filled with values that parse and fall inside every bound, so what is under test
 * is only *which* fields are present: the one thing [records] decides.
 */
class SetEntryRecordsTest {

    @Test
    fun `the tick is live exactly when SetValidator would accept the completed set`() {
        ExerciseType.entries.forEach { type ->
            FIELD_COMBINATIONS.forEach { present ->
                val entry = entryWith(present)
                val accepted = SetValidator.validate(completedSetWith(present), type) == ValidationResult.Valid
                // `canLog` is the half about the values: an empty row is never loggable, whatever
                // the validator would say of a set with nothing in it.
                val live = entry.canLog && entry.records(type)

                assertEquals("$type with $present", accepted && present.isNotEmpty(), live)
            }
        }
    }

    @Test
    fun `a strength set records its reps`() {
        assertTrue(SetEntry(reps = "5").records(ExerciseType.STRENGTH))
        assertFalse(SetEntry(weight = "80").records(ExerciseType.STRENGTH))
        assertFalse(SetEntry(duration = "1:30").records(ExerciseType.STRENGTH))
    }

    @Test
    fun `a run records its time, with or without a distance`() {
        assertTrue(SetEntry(duration = "27:14").records(ExerciseType.ENDURANCE))
        assertTrue(SetEntry(duration = "27:14", distance = "5").records(ExerciseType.ENDURANCE))
        assertFalse(SetEntry(distance = "5").records(ExerciseType.ENDURANCE))
    }

    @Test
    fun `a hold records its time, with or without an added weight`() {
        assertTrue(SetEntry(duration = "1:30").records(ExerciseType.ISOMETRIC))
        assertTrue(SetEntry(duration = "1:30", weight = "10").records(ExerciseType.ISOMETRIC))
        assertFalse(SetEntry(weight = "10").records(ExerciseType.ISOMETRIC))
        assertFalse(SetEntry(reps = "60").records(ExerciseType.ISOMETRIC))
    }

    @Test
    fun `an exercise gone from the library is checked against the bounds alone`() {
        assertTrue(SetEntry(weight = "80").records(null))
    }

    @Test
    fun `a time or a distance alone is enough to start a set and to be loggable as values`() {
        assertTrue(SetEntry(duration = "1:30").canLog)
        assertTrue(SetEntry(distance = "5").canLog)
        assertTrue(SetEntry(duration = "1:30").isStarted)
        assertFalse(SetEntry(duration = "1:30", error = SetEntryError.DurationNotATime).canLog)
    }

    private enum class Field { WEIGHT, REPS, DURATION, DISTANCE }

    private fun entryWith(present: Set<Field>) =
        SetEntry(
            weight = if (Field.WEIGHT in present) "10" else "",
            reps = if (Field.REPS in present) "5" else "",
            duration = if (Field.DURATION in present) "1:30" else "",
            distance = if (Field.DISTANCE in present) "5" else "",
        )

    private fun completedSetWith(present: Set<Field>) =
        WorkoutSet(
            sessionExerciseId = 1,
            setNumber = 1,
            weight = 10_000_000L.takeIf { Field.WEIGHT in present },
            reps = 5.takeIf { Field.REPS in present },
            duration = 90L.takeIf { Field.DURATION in present },
            distance = 500_000L.takeIf { Field.DISTANCE in present },
            isCompleted = true,
            completedAt = 1,
            createdAt = 1,
            updatedAt = 1,
        )

    private companion object {
        /** Every subset of the four fields, the empty one included. */
        val FIELD_COMBINATIONS: List<Set<Field>> =
            (0 until (1 shl Field.entries.size)).map { mask ->
                Field.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            }
    }
}
