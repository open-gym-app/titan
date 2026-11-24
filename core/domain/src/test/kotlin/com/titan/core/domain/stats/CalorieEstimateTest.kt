package com.titan.core.domain.stats

import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.stats.CalorieEstimate.ENDURANCE_MET
import com.titan.core.domain.stats.CalorieEstimate.ISOMETRIC_MET
import com.titan.core.domain.stats.CalorieEstimate.RECOVERY_MET
import com.titan.core.domain.stats.CalorieEstimate.REFERENCE_BODY_MASS_MG
import com.titan.core.domain.stats.CalorieEstimate.STRENGTH_MET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Like `TemplateDurationTest`, the expectations are written as the formula — MET × kg × hours —
 * so a failure says which rule moved rather than only that a number did.
 */
class CalorieEstimateTest {

    private val kg = 80.0
    private val bodyMass = 80_000_000L
    private val defaultRest = 180.seconds

    private val catalogue = mutableMapOf<Long, Exercise>()
    private val exercises = mutableListOf<SessionExercise>()
    private val sets = mutableListOf<WorkoutSet>()

    @Test
    fun `a session with nothing performed has no estimate`() {
        val bench = exercise(ExerciseType.STRENGTH)
        set(bench, reps = 10, completed = false)

        assertNull(estimate())
    }

    @Test
    fun `an hour of lifting is priced at the resistance-training MET over work and rest`() {
        // Six exercises of three sets of ten on the three-minute rest: 18 × 30 s of work and 17
        // rests, an hour to the second.
        repeat(6) {
            val lift = exercise(ExerciseType.STRENGTH)
            repeat(3) { set(lift, reps = 10) }
        }

        assertEquals(kcal(STRENGTH_MET, 18 * 30 + 17 * 180), estimate())
        assertEquals((STRENGTH_MET * kg).roundToInt(), estimate())
    }

    @Test
    fun `heavier sets of fewer reps cost less work time than lighter ones of more`() {
        val heavy = exercise(ExerciseType.STRENGTH, rest = 60)
        repeat(2) { set(heavy, reps = 3) }

        assertEquals(kcal(STRENGTH_MET, 2 * 3 * 3 + 60), estimate())
    }

    @Test
    fun `a run is priced at the cardio MET for as long as it lasted`() {
        val run = exercise(ExerciseType.ENDURANCE)
        set(run, duration = 1_800)

        assertEquals(kcal(ENDURANCE_MET, 1_800), estimate())
    }

    @Test
    fun `the rest between cardio intervals is priced as recovery, not as the effort`() {
        val intervals = exercise(ExerciseType.ENDURANCE, rest = 120)
        repeat(3) { set(intervals, duration = 240) }

        val expected = (ENDURANCE_MET * 3 * 240 + RECOVERY_MET * 2 * 120) * kg / 3_600
        assertEquals(expected.roundToInt(), estimate())
    }

    @Test
    fun `a hold is priced at the light-calisthenics MET`() {
        val plank = exercise(ExerciseType.ISOMETRIC, rest = 60)
        repeat(3) { set(plank, duration = 60) }

        assertEquals(kcal(ISOMETRIC_MET, 3 * 60 + 2 * 60), estimate())
    }

    @Test
    fun `an exercise the catalogue no longer has is priced as a lift`() {
        val orphan = exercise(ExerciseType.STRENGTH)
        catalogue.remove(orphan.exerciseId)
        repeat(2) { set(orphan, reps = 10) }

        // Its rest is the user's default, for the same reason.
        assertEquals(kcal(STRENGTH_MET, 2 * 30 + 180), estimate())
    }

    @Test
    fun `an unticked set is not work`() {
        val bench = exercise(ExerciseType.STRENGTH, rest = 60)
        set(bench, reps = 10)
        set(bench, reps = 10, completed = false)

        assertEquals(kcal(STRENGTH_MET, 30), estimate())
    }

    @Test
    fun `the estimate is proportional to body mass`() {
        val run = exercise(ExerciseType.ENDURANCE)
        set(run, duration = 3_600)

        assertEquals((ENDURANCE_MET * 60).roundToInt(), estimate(bodyMass = 60_000_000L))
        assertEquals((ENDURANCE_MET * 100).roundToInt(), estimate(bodyMass = 100_000_000L))
    }

    @Test
    fun `the exercises are priced in position order, whatever order they arrive in`() {
        // The last rest of the workout is dropped. If the order were the read order, the run's
        // would be — and the bench's, which is taken, would be lost.
        val run = exercise(ExerciseType.ENDURANCE, rest = 600, position = 0)
        val bench = exercise(ExerciseType.STRENGTH, rest = 60, position = 1)
        set(run, duration = 600)
        set(bench, reps = 10)
        exercises.reverse()

        val expected = (ENDURANCE_MET * 600 + RECOVERY_MET * 600 + STRENGTH_MET * 30) * kg / 3_600
        assertEquals(expected.roundToInt(), estimate())
    }

    @Test
    fun `negative - a figure past the validator's ceiling is no estimate`() {
        // Ten days on the move at the heaviest weight the body screen accepts.
        val ultra = exercise(ExerciseType.ENDURANCE)
        set(ultra, duration = 864_000)

        assertNull(estimate(bodyMass = 1_000_000_000L))
    }

    @Test
    fun `negative - a session too short to burn one kilocalorie is no estimate`() {
        val curl = exercise(ExerciseType.STRENGTH)
        set(curl, reps = 1)

        assertNull(estimate(bodyMass = 1_000_000L))
    }

    // ─── Body mass ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the body mass is the latest weight taken at or before the session`() {
        val history = listOf(
            measurement(at = 100, weight = 70_000_000L),
            measurement(at = 300, weight = 75_000_000L),
            measurement(at = 900, weight = 90_000_000L),
        )

        assertEquals(75_000_000L, CalorieEstimate.bodyMassAt(history, at = 500))
        assertEquals(75_000_000L, CalorieEstimate.bodyMassAt(history, at = 300))
    }

    @Test
    fun `an entry recording no weight is passed over for the one before it`() {
        val history = listOf(
            measurement(at = 400, weight = null),
            measurement(at = 100, weight = 70_000_000L),
        )

        assertEquals(70_000_000L, CalorieEstimate.bodyMassAt(history, at = 500))
    }

    @Test
    fun `a user who never weighed themselves is priced at the reference body mass`() {
        assertEquals(REFERENCE_BODY_MASS_MG, CalorieEstimate.bodyMassAt(emptyList(), at = 500))
        assertEquals(
            REFERENCE_BODY_MASS_MG,
            CalorieEstimate.bodyMassAt(listOf(measurement(at = 900, weight = 70_000_000L)), at = 500),
        )
    }

    @Test
    fun `negative - a weight the validator refuses is passed over`() {
        val history = listOf(
            measurement(at = 400, weight = 0L),
            measurement(at = 300, weight = Long.MAX_VALUE),
            measurement(at = 100, weight = 70_000_000L),
        )

        assertEquals(70_000_000L, CalorieEstimate.bodyMassAt(history, at = 500))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────────────

    private fun estimate(
        bodyMass: Long = this.bodyMass,
        rest: Duration = defaultRest,
    ): Int? = CalorieEstimate.of(exercises, sets, rest, catalogue, bodyMass)

    /** [met] over [seconds] at [kg], rounded as the estimate rounds. */
    private fun kcal(
        met: Double,
        seconds: Int,
    ): Int = (met * kg * seconds / 3_600).roundToInt()

    /** A session exercise over a catalogue entry of its own, at the end of the session. */
    private fun exercise(
        type: ExerciseType,
        rest: Long? = null,
        position: Int = exercises.size,
    ): SessionExercise {
        val id = catalogue.size + 1L
        catalogue[id] = Exercise(id = id, name = "Exercise $id", type = type, defaultRest = rest)
        return SessionExercise(
            id = id,
            sessionId = 1,
            exerciseId = id,
            position = position,
            createdAt = 0,
            updatedAt = 0,
        ).also(exercises::add)
    }

    private fun set(
        exercise: SessionExercise,
        reps: Int? = null,
        duration: Long? = null,
        completed: Boolean = true,
    ) {
        sets += WorkoutSet(
            sessionExerciseId = exercise.id,
            setNumber = sets.count { it.sessionExerciseId == exercise.id } + 1,
            reps = reps,
            duration = duration,
            isCompleted = completed,
            completedAt = 0,
            createdAt = 0,
            updatedAt = 0,
        )
    }

    private fun measurement(
        at: Long,
        weight: Long?,
    ) = BodyMeasurement(weight = weight, waist = 800, createdAt = at, updatedAt = at)
}
