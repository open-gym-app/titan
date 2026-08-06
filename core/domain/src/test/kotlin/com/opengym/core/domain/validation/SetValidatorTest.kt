package com.opengym.core.domain.validation

import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bounds the schema cannot express. Every case uses a fixed input and an
 * exact expected outcome — a rule that only asserts "some failure" would pass even
 * if the validator started refusing valid sets for the wrong reason.
 */
class SetValidatorTest {

    private fun set(
        reps: Int? = 10,
        weight: Long? = 100_000_000L,
        duration: Long? = null,
        distance: Long? = null,
        restAfter: Long? = 90L,
        isCompleted: Boolean = true,
    ) = WorkoutSet(
        sessionExerciseId = 1,
        setNumber = 1,
        reps = reps,
        weight = weight,
        duration = duration,
        distance = distance,
        restAfter = restAfter,
        isCompleted = isCompleted,
        completedAt = if (isCompleted) 1L else 0L,
        createdAt = 0,
        updatedAt = 0,
    )

    /** An endurance set as it is actually logged: a run of 30 minutes over 5 km. */
    private fun enduranceSet(
        duration: Long? = 1_800L,
        distance: Long? = 500_000L,
        isCompleted: Boolean = true,
    ) = set(
        reps = null,
        weight = null,
        duration = duration,
        distance = distance,
        isCompleted = isCompleted,
    )

    private fun reasonOf(set: WorkoutSet, exerciseType: ExerciseType? = null): String {
        val result = SetValidator.validate(set, exerciseType)
        assertTrue("expected $set to be refused, got $result", result is ValidationResult.Invalid)
        return (result as ValidationResult.Invalid).reason
    }

    @Test
    fun `a typical working set is valid`() {
        assertEquals(ValidationResult.Valid, SetValidator.validate(set()))
    }

    @Test
    fun `an endurance set of duration and distance is valid`() {
        assertEquals(ValidationResult.Valid, SetValidator.validate(enduranceSet()))
    }

    @Test
    fun `every bound is inclusive at its edges`() {
        val atLowerBounds = set(
            reps = SetValidator.REPS_MIN,
            weight = SetValidator.WEIGHT_MIN_MG,
            duration = SetValidator.DURATION_MIN_SECONDS,
            distance = SetValidator.DISTANCE_MIN_CM,
            restAfter = SetValidator.REST_MIN_SECONDS,
        )
        assertEquals(ValidationResult.Valid, SetValidator.validate(atLowerBounds))

        val atUpperBounds = set(
            reps = SetValidator.REPS_MAX,
            weight = SetValidator.WEIGHT_MAX_MG,
            duration = SetValidator.DURATION_MAX_SECONDS,
            distance = SetValidator.DISTANCE_MAX_CM,
        )
        assertEquals(ValidationResult.Valid, SetValidator.validate(atUpperBounds))
    }

    @Test
    fun `null fields are not violations`() {
        // A timed hold or a cardio interval carries no reps and no weight.
        assertEquals(
            ValidationResult.Valid,
            SetValidator.validate(
                set(reps = null, weight = null, duration = null, distance = null, restAfter = null),
            ),
        )
    }

    @Test
    fun `zero reps is refused with a reason naming reps`() {
        val reason = reasonOf(set(reps = 0))
        assertTrue(reason.isNotBlank())
        assertTrue("reason should name the field: $reason", reason.contains("reps"))
        assertTrue("reason should quote the value: $reason", reason.contains("0"))
    }

    @Test
    fun `reps just above the ceiling is refused`() {
        assertTrue(reasonOf(set(reps = SetValidator.REPS_MAX + 1)).contains("reps"))
    }

    @Test
    fun `the reps ceiling is ten thousand`() {
        assertEquals(10_000, SetValidator.REPS_MAX)
    }

    @Test
    fun `negative reps is refused`() {
        assertTrue(reasonOf(set(reps = -1)).contains("reps"))
    }

    @Test
    fun `negative weight is refused with a reason naming weight`() {
        val reason = reasonOf(set(weight = -1L))
        assertTrue(reason.isNotBlank())
        assertTrue("reason should name the field: $reason", reason.contains("weight"))
    }

    @Test
    fun `weight above the ceiling is refused`() {
        val reason = reasonOf(set(weight = SetValidator.WEIGHT_MAX_MG + 1))
        assertTrue("reason should name the field: $reason", reason.contains("weight"))
    }

    @Test
    fun `the weight ceiling is ten tonnes`() {
        // Guards the unit: the bound is in milligrams.
        assertEquals(10_000_000_000L, SetValidator.WEIGHT_MAX_MG)
    }

    @Test
    fun `a heavy sled push is under the weight ceiling`() {
        assertEquals(ValidationResult.Valid, SetValidator.validate(set(reps = 1, weight = 500_000_000L)))
    }

    @Test
    fun `zero duration is refused with a reason naming duration`() {
        val reason = reasonOf(enduranceSet(duration = 0L))
        assertTrue(reason.isNotBlank())
        assertTrue("reason should name the field: $reason", reason.contains("duration"))
    }

    @Test
    fun `negative duration is refused`() {
        assertTrue(reasonOf(enduranceSet(duration = -1L)).contains("duration"))
    }

    @Test
    fun `a duration above the ceiling is refused`() {
        val reason = reasonOf(enduranceSet(duration = SetValidator.DURATION_MAX_SECONDS + 1))
        assertTrue("reason should name the field: $reason", reason.contains("duration"))
    }

    @Test
    fun `the duration ceiling is ten days`() {
        assertEquals(10 * 24 * 60 * 60L, SetValidator.DURATION_MAX_SECONDS)
    }

    @Test
    fun `a full distance ironman fits under the bounds`() {
        // 226 km inside the 17-hour cutoff: the case a 24-hour ceiling would have
        // been fine with, but which a tighter distance bound would have refused.
        val ironman = enduranceSet(duration = 17 * 60 * 60L, distance = 22_600_000L)
        assertEquals(ValidationResult.Valid, SetValidator.validate(ironman, ExerciseType.ENDURANCE))
    }

    @Test
    fun `a multi day ultra fits under the bounds`() {
        // A 6-day race: the effort a 24-hour duration ceiling used to reject outright.
        val sixDayRace = enduranceSet(duration = 6 * 24 * 60 * 60L, distance = 100_000_000L)
        assertEquals(ValidationResult.Valid, SetValidator.validate(sixDayRace, ExerciseType.ENDURANCE))
    }

    @Test
    fun `a high rep conditioning set is under the reps ceiling`() {
        assertEquals(ValidationResult.Valid, SetValidator.validate(set(reps = 5_000)))
    }

    @Test
    fun `negative distance is refused with a reason naming distance`() {
        val reason = reasonOf(enduranceSet(distance = -1L))
        assertTrue(reason.isNotBlank())
        assertTrue("reason should name the field: $reason", reason.contains("distance"))
    }

    @Test
    fun `distance above the ceiling is refused`() {
        val reason = reasonOf(enduranceSet(distance = SetValidator.DISTANCE_MAX_CM + 1))
        assertTrue("reason should name the field: $reason", reason.contains("distance"))
    }

    @Test
    fun `the distance ceiling is ten thousand kilometres`() {
        // Guards the unit: the bound is in centimeters, so 10,000 km is 1e9, not 1e12.
        assertEquals(10_000L * 100_000L, SetValidator.DISTANCE_MAX_CM)
    }

    @Test
    fun `distance is optional on an endurance set`() {
        assertEquals(
            ValidationResult.Valid,
            SetValidator.validate(enduranceSet(distance = null), ExerciseType.ENDURANCE),
        )
    }

    @Test
    fun `zero distance is allowed`() {
        assertEquals(ValidationResult.Valid, SetValidator.validate(enduranceSet(distance = 0L)))
    }

    @Test
    fun `a completed endurance set without a duration is refused`() {
        val reason = reasonOf(enduranceSet(duration = null), ExerciseType.ENDURANCE)
        assertTrue("reason should name the type: $reason", reason.contains("ENDURANCE"))
        assertTrue("reason should name the field: $reason", reason.contains("duration"))
    }

    @Test
    fun `a completed isometric set without a duration is refused`() {
        val reason = reasonOf(enduranceSet(duration = null, distance = null), ExerciseType.ISOMETRIC)
        assertTrue("reason should name the type: $reason", reason.contains("ISOMETRIC"))
    }

    @Test
    fun `a completed strength set without reps is refused`() {
        val reason = reasonOf(set(reps = null), ExerciseType.STRENGTH)
        assertTrue("reason should name the type: $reason", reason.contains("STRENGTH"))
        assertTrue("reason should name the field: $reason", reason.contains("reps"))
    }

    @Test
    fun `a planned set may still be missing its dimension`() {
        // Planned-but-unperformed work is stored with is_completed = 0 and empty
        // dimensions; refusing it would make a workout impossible to plan ahead.
        assertEquals(
            ValidationResult.Valid,
            SetValidator.validate(
                enduranceSet(duration = null, distance = null, isCompleted = false),
                ExerciseType.ENDURANCE,
            ),
        )
        assertEquals(
            ValidationResult.Valid,
            SetValidator.validate(set(reps = null, isCompleted = false), ExerciseType.STRENGTH),
        )
    }

    @Test
    fun `completeness is only checked when the exercise type is known`() {
        // Without a type the validator cannot know which dimension is required,
        // so it checks the bounds alone rather than guessing.
        assertEquals(ValidationResult.Valid, SetValidator.validate(set(reps = null)))
        assertEquals(ValidationResult.Valid, SetValidator.validate(enduranceSet(duration = null)))
    }

    @Test
    fun `a strength set may also carry a duration and a distance`() {
        // Timing a set of squats or logging a loaded carry is legitimate; the type
        // rule requires its own dimension, it does not forbid the others.
        val timedCarry = set(reps = 8, weight = 60_000_000L, duration = 45L, distance = 2_000L)
        assertEquals(ValidationResult.Valid, SetValidator.validate(timedCarry, ExerciseType.STRENGTH))
    }

    @Test
    fun `every exercise type is accepted by a set that fills its dimension`() {
        ExerciseType.entries.forEach { type ->
            val filled = when (type) {
                ExerciseType.STRENGTH -> set()
                ExerciseType.ENDURANCE, ExerciseType.ISOMETRIC -> enduranceSet()
            }
            assertEquals(
                "$type should accept $filled",
                ValidationResult.Valid,
                SetValidator.validate(filled, type),
            )
        }
    }

    @Test
    fun `negative rest is refused with a reason naming rest`() {
        val reason = reasonOf(set(restAfter = -1L))
        assertTrue("reason should name the field: $reason", reason.contains("restAfter"))
    }

    @Test
    fun `zero rest is allowed`() {
        assertEquals(ValidationResult.Valid, SetValidator.validate(set(restAfter = 0L)))
    }

    @Test
    fun `several violations are all reported in one reason`() {
        val reason = reasonOf(
            set(reps = 0, weight = -5L, duration = -1L, distance = -2L, restAfter = -7L),
        )
        assertTrue("missing reps: $reason", reason.contains("reps"))
        assertTrue("missing weight: $reason", reason.contains("weight"))
        assertTrue("missing duration: $reason", reason.contains("duration"))
        assertTrue("missing distance: $reason", reason.contains("distance"))
        assertTrue("missing rest: $reason", reason.contains("restAfter"))
    }

    @Test
    fun `a bound violation and a missing dimension are reported together`() {
        val reason = reasonOf(enduranceSet(duration = null, distance = -2L), ExerciseType.ENDURANCE)
        assertTrue("missing distance bound: $reason", reason.contains("distance"))
        assertTrue("missing shape rule: $reason", reason.contains("ENDURANCE"))
    }

    @Test
    fun `valid exposes isValid and no reason`() {
        val result = SetValidator.validate(set())
        assertTrue(result.isValid)
        assertNull(result.reasonOrNull())
    }

    @Test
    fun `invalid exposes isValid false and its reason`() {
        val result = SetValidator.validate(set(reps = 0))
        assertFalse(result.isValid)
        assertEquals((result as ValidationResult.Invalid).reason, result.reasonOrNull())
    }

    @Test
    fun `an invalid result cannot be constructed without a reason`() {
        assertThrows(IllegalArgumentException::class.java) { ValidationResult.Invalid("") }
        assertThrows(IllegalArgumentException::class.java) { ValidationResult.Invalid("   ") }
    }
}
