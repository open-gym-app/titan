package com.opengym.core.domain.warmup

import com.opengym.core.domain.model.Discipline
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MechanicType
import com.opengym.core.domain.model.MobilityExercise
import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SessionMovement
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warm-up and cool-down planner (#214).
 *
 * Every case is a fixture of a few rows and an assertion about *which muscles* were chosen
 * rather than which names: the names are the catalogue's, and a test that pinned them would
 * fail the day the seed is regenerated for an unrelated reason.
 */
class WarmupPlannerTest {

    private val preferences = UserPreferences(
        unitSystem = UnitSystem.METRIC,
        plateInventory = PlateInventory.METRIC,
        barWeight = 20_000_000L,
    )

    /** A push day: bench with its shoulder and triceps assistance, then a pushdown. */
    private fun pushDay(workingWeight: Long? = 80_000_000L): List<SessionMovement> =
        listOf(
            lift(1, "Bench press", CHEST, secondary = listOf(SHOULDERS, TRICEPS), workingWeight = workingWeight),
            lift(2, "Triceps pushdown", TRICEPS, mechanic = MechanicType.ISOLATION, workingWeight = 30_000_000L),
        )

    private fun plan(
        movements: List<SessionMovement>,
        sessionId: Long = SESSION,
        catalogue: List<MobilityExercise> = CATALOGUE,
        preferences: UserPreferences = this.preferences,
    ) = WarmupPlanner.plan(
        sessionId = sessionId,
        movements = movements,
        catalogue = catalogue,
        preferences = preferences,
    )

    // ─── What gets suggested ────────────────────────────────────────────────────────────

    @Test
    fun `a chest and triceps session warms up the muscles it touches`() {
        val suggestion = plan(pushDay())

        assertTrue(suggestion.warmup.isNotEmpty())
        // Chest, shoulders or triceps — the shoulders arrive through the bench press's
        // *secondary* link, which is the whole point of reading both.
        assertTrue(
            suggestion.warmup.map { it.muscleId() }
                .all { it in setOf(CHEST.id, SHOULDERS.id, TRICEPS.id) },
        )
        assertTrue(suggestion.warmup.all(MobilityExercise::isDynamic))
    }

    @Test
    fun `the cool-down is static stretches for the same muscles`() {
        val suggestion = plan(pushDay())

        assertTrue(suggestion.cooldown.isNotEmpty())
        assertTrue(
            suggestion.cooldown.map { it.muscleId() }
                .all { it in setOf(CHEST.id, SHOULDERS.id, TRICEPS.id) },
        )
        assertTrue(suggestion.cooldown.none(MobilityExercise::isDynamic))
        // A plyometric movement the seed left untagged is not a stretch, whatever its tag says.
        assertTrue(suggestion.cooldown.none { it.exercise.discipline == Discipline.PLYOMETRICS })
    }

    @Test
    fun `nothing is suggested more than three times over`() {
        val suggestion = plan(pushDay())

        assertTrue(suggestion.warmup.size in 2..3)
        assertTrue(suggestion.cooldown.size in 2..3)
    }

    @Test
    fun `the pick covers different muscles rather than repeating one`() {
        val suggestion = plan(
            listOf(
                lift(1, "Squat", QUADRICEPS, workingWeight = 100_000_000L),
                lift(2, "Romanian deadlift", HAMSTRINGS, workingWeight = 80_000_000L),
            ),
        )

        // Two prime movers, two movements, and they are not both for the same one — the
        // catalogue holds two dynamic candidates per muscle, so a naive pick could take both.
        assertEquals(2, suggestion.warmup.size)
        assertEquals(setOf(QUADRICEPS.id, HAMSTRINGS.id), suggestion.warmup.map { it.muscleId() }.toSet())
    }

    @Test
    fun `a session over more than two muscle groups gets the general note`() {
        val threeWay = listOf(
            lift(1, "Bench press", CHEST, workingWeight = 80_000_000L),
            lift(2, "Squat", QUADRICEPS, workingWeight = 100_000_000L),
            lift(3, "Overhead press", SHOULDERS, workingWeight = 40_000_000L),
        )

        assertTrue(plan(threeWay).includeGeneralNote)
        assertTrue(!plan(pushDay()).includeGeneralNote)
    }

    // ─── The ramp-up ────────────────────────────────────────────────────────────────────

    @Test
    fun `the first compound lift gets two loadable warm-up sets`() {
        val suggestion = plan(pushDay())

        assertEquals(1L, suggestion.rampUpExerciseId)
        assertEquals(listOf(SetType.WARMUP, SetType.WARMUP), suggestion.rampUp.map(PlannedSet::setType))
        // Half and three quarters of 80 kg, on a 20 kg bar and the standard metric rack: both
        // land exactly, and both are under the working weight.
        assertEquals(listOf(40_000_000L, 60_000_000L), suggestion.rampUp.map { it.weight })
        assertEquals(listOf(5, 3), suggestion.rampUp.map(PlannedSet::reps))
    }

    @Test
    fun `an isolation lift is not ramped into even when it is first`() {
        val suggestion = plan(
            listOf(
                lift(1, "Triceps pushdown", TRICEPS, mechanic = MechanicType.ISOLATION, workingWeight = 30_000_000L),
                lift(2, "Bench press", CHEST, workingWeight = 80_000_000L),
            ),
        )

        assertEquals(2L, suggestion.rampUpExerciseId)
    }

    @Test
    fun `an odd working weight is rounded to something the rack can build`() {
        val suggestion = plan(
            listOf(lift(1, "Bench press", CHEST, workingWeight = 77_500_000L)),
        )

        // 38.75 kg and 58.125 kg are not loadable on a 20 kg bar with 1.25 kg as the smallest
        // pair; 37.5 and 57.5 are, and both round down rather than up.
        assertEquals(listOf(37_500_000L, 57_500_000L), suggestion.rampUp.map { it.weight })
    }

    @Test
    fun `a bodyweight session gets mobility and no ramp-up`() {
        val suggestion = plan(
            listOf(
                lift(1, "Press-up", CHEST, equipment = EquipmentType.BODYWEIGHT, workingWeight = null),
                lift(2, "Pull-up", SHOULDERS, equipment = EquipmentType.BODYWEIGHT, workingWeight = null),
            ),
        )

        assertTrue(suggestion.warmup.isNotEmpty())
        assertTrue(suggestion.cooldown.isNotEmpty())
        assertTrue(suggestion.rampUp.isEmpty())
        assertNull(suggestion.rampUpExerciseId)
    }

    @Test
    fun `a cardio session gets mobility and no ramp-up`() {
        val suggestion = plan(
            listOf(
                lift(
                    id = 1,
                    name = "Treadmill",
                    primary = QUADRICEPS,
                    equipment = EquipmentType.MACHINE,
                    workingWeight = null,
                    type = ExerciseType.ENDURANCE,
                    discipline = Discipline.CARDIO,
                ),
            ),
        )

        assertTrue(suggestion.warmup.isNotEmpty())
        assertTrue(suggestion.rampUp.isEmpty())
    }

    @Test
    fun `a light lift ramps up with the empty bar once rather than twice`() {
        val suggestion = plan(
            // 22.5 kg on a 20 kg bar: half of it and three quarters of it are both under the
            // bar, so both round to the bar — and two sets at the same weight is one set.
            listOf(lift(1, "Bench press", CHEST, workingWeight = 22_500_000L)),
        )

        assertEquals(listOf(20_000_000L), suggestion.rampUp.map { it.weight })
    }

    @Test
    fun `a lift at the bar itself gets no ramp-up at all`() {
        val suggestion = plan(
            // Nothing lighter than the empty bar exists, so every fraction of it rounds back to
            // the working weight — and a "warm-up" at the working weight is a working set.
            listOf(lift(1, "Bench press", CHEST, workingWeight = 20_000_000L)),
        )

        assertTrue(suggestion.rampUp.isEmpty())
        assertNull(suggestion.rampUpExerciseId)
    }

    // ─── Nothing to say ─────────────────────────────────────────────────────────────────

    @Test
    fun `an empty session suggests nothing`() {
        val suggestion = plan(emptyList())

        assertTrue(!suggestion.hasWarmup)
        assertTrue(!suggestion.hasCooldown)
    }

    @Test
    fun `a session that is only stretching has nothing to warm up for`() {
        val suggestion = plan(
            listOf(
                lift(
                    id = 1,
                    name = "Chest stretch",
                    primary = CHEST,
                    mechanic = null,
                    equipment = EquipmentType.BODYWEIGHT,
                    type = ExerciseType.ISOMETRIC,
                    discipline = Discipline.STRETCHING,
                ),
            ),
        )

        assertTrue(!suggestion.hasWarmup)
        assertTrue(!suggestion.hasCooldown)
    }

    @Test
    fun `a catalogue with no match for the session suggests no movements`() {
        val suggestion = plan(
            movements = pushDay(),
            catalogue = CATALOGUE.filter { it.muscleId() in setOf(QUADRICEPS.id, HAMSTRINGS.id) },
        )

        assertTrue(suggestion.warmup.isEmpty())
        assertTrue(suggestion.cooldown.isEmpty())
        // The ramp-up is about the lift and not about the catalogue, so it survives.
        assertEquals(1L, suggestion.rampUpExerciseId)
    }

    // ─── Stability ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the same session is planned the same way every time`() {
        assertEquals(plan(pushDay()), plan(pushDay()))
    }

    @Test
    fun `the pick does not move when the catalogue arrives in a different order`() {
        val reversed = plan(movements = pushDay(), catalogue = CATALOGUE.reversed())

        assertEquals(plan(pushDay()), reversed)
    }

    @Test
    fun `two sessions over the same muscles are not given the same movements`() {
        val first = plan(pushDay(), sessionId = 1L)
        val second = plan(pushDay(), sessionId = 2L)

        // Not a promise the planner makes for *every* pair of ids — it is a hash, and two of
        // them may collide — but the whole point of seeding on the session is that they
        // usually differ, and these two do.
        assertTrue(first.warmup != second.warmup || first.cooldown != second.cooldown)
    }

    @Test
    fun `adding the suggestion back into the session does not change it`() {
        val suggestion = plan(pushDay())
        // What accepting the card does: the movements become exercises of the session. They are
        // mobility work, so the planner leaves them out of the profile and answers identically.
        val accepted = pushDay() + suggestion.warmup.map { movement ->
            SessionMovement(
                exercise = movement.exercise,
                muscleGroups = links(CHEST),
            )
        }

        assertEquals(suggestion, plan(accepted))
    }

    private companion object {
        const val SESSION = 42L
    }
}
