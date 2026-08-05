package com.opengym.core.domain.stats

import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One kilogram in the canonical storage unit, matching [VolumeCalculatorTest]. */
private const val KG = 1_000_000L

/**
 * The real [MuscleGroup] data class, with ids standing in for `muscle_group` rows and
 * names taken from the seeded 17-value vocabulary. Nothing here keys on the name.
 */
private val CHEST = MuscleGroup(id = 6, name = "CHEST")
private val TRICEPS = MuscleGroup(id = 17, name = "TRICEPS")
private val SHOULDERS = MuscleGroup(id = 15, name = "SHOULDERS")
private val QUADRICEPS = MuscleGroup(id = 14, name = "QUADRICEPS")
private val GLUTES = MuscleGroup(id = 8, name = "GLUTES")

private fun primary(group: MuscleGroup) = ExerciseMuscleGroupLink(group, isPrimary = true)

private fun secondary(group: MuscleGroup) = ExerciseMuscleGroupLink(group, isPrimary = false)

private fun set(
    sessionExerciseId: Long = 1,
    reps: Int? = 10,
    weight: Long? = 100 * KG,
    isCompleted: Boolean = true,
) = WorkoutSet(
    sessionExerciseId = sessionExerciseId,
    setNumber = 1,
    reps = reps,
    weight = weight,
    isCompleted = isCompleted,
    completedAt = if (isCompleted) 1L else 0L,
    createdAt = 0,
    updatedAt = 0,
)

private fun sessionExercise(id: Long, exerciseId: Long) =
    SessionExercise(id = id, sessionId = 1, exerciseId = exerciseId, position = 0, createdAt = 0, updatedAt = 0)

/**
 * Volume is integer milligram-reps, so every expectation below is an exact equality.
 * The figures are chosen to be readable in kilograms: a 100 kg x 10 set is 1000 kg,
 * and half of it is 500 kg.
 */
class MuscleVolumeCalculatorTest {

    @Test
    fun `a primary link takes the whole set and a secondary link takes half`() {
        val links = mapOf(1L to listOf(primary(CHEST), secondary(TRICEPS)))

        val volume = MuscleVolumeCalculator.volumeByMuscle(listOf(set()), links)

        assertEquals(1_000 * KG, volume[CHEST])
        assertEquals(500 * KG, volume[TRICEPS])
    }

    @Test
    fun `an exercise with no secondary links attributes only to its primary`() {
        val links = mapOf(1L to listOf(primary(CHEST)))

        val volume = MuscleVolumeCalculator.volumeByMuscle(listOf(set()), links)

        assertEquals(mapOf(CHEST to 1_000 * KG), volume)
    }

    @Test
    fun `every secondary link takes half, so four of them attribute three times the set`() {
        val links = mapOf(
            1L to listOf(
                primary(QUADRICEPS),
                secondary(GLUTES),
                secondary(CHEST),
                secondary(SHOULDERS),
                secondary(TRICEPS),
            ),
        )

        val volume = MuscleVolumeCalculator.volumeByMuscle(listOf(set()), links)

        assertEquals(1_000 * KG, volume[QUADRICEPS])
        listOf(GLUTES, CHEST, SHOULDERS, TRICEPS).forEach {
            assertEquals("wrong share for ${it.name}", 500 * KG, volume[it])
        }
        // Pinned deliberately: attributed volume is not conserved. A secondary link
        // takes a flat half however many there are, so a five-link exercise credits
        // 3x the 1000 kg actually lifted. These figures track a muscle against itself
        // over time; they are not a share-of-effort split of the session.
        assertEquals(3_000 * KG, volume.values.sum())
    }

    @Test
    fun `sets across several exercises accumulate per muscle group`() {
        // Bench (chest primary, triceps secondary) then a triceps pushdown.
        val links = mapOf(
            1L to listOf(primary(CHEST), secondary(TRICEPS)),
            2L to listOf(primary(TRICEPS)),
        )
        val sets = listOf(
            set(sessionExerciseId = 1),
            set(sessionExerciseId = 1),
            set(sessionExerciseId = 2, weight = 30 * KG),
        )

        val volume = MuscleVolumeCalculator.volumeByMuscle(sets, links)

        assertEquals(2_000 * KG, volume[CHEST])
        // 500 + 500 as bench's secondary, then 300 of its own.
        assertEquals(1_300 * KG, volume[TRICEPS])
    }

    @Test
    fun `the same exercise performed twice in a session resolves through both rows`() {
        // Two session_exercise rows pointing at one exercise - the case that makes
        // the link map key a session exercise id rather than an exercise id.
        val links = mapOf(
            1L to listOf(primary(CHEST), secondary(TRICEPS)),
            2L to listOf(primary(CHEST), secondary(TRICEPS)),
        )
        val sets = listOf(set(sessionExerciseId = 1), set(sessionExerciseId = 2))

        val volume = MuscleVolumeCalculator.volumeByMuscle(sets, links)

        assertEquals(2_000 * KG, volume[CHEST])
        assertEquals(1_000 * KG, volume[TRICEPS])
    }

    @Test
    fun `incomplete sets do not contribute`() {
        val links = mapOf(1L to listOf(primary(CHEST)))
        val sets = listOf(set(), set(isCompleted = false))

        val volume = MuscleVolumeCalculator.volumeByMuscle(sets, links)

        assertEquals(1_000 * KG, volume[CHEST])
    }

    @Test
    fun `a set whose exercise has no muscle links is dropped rather than failing`() {
        val volume = MuscleVolumeCalculator.volumeByMuscle(listOf(set(sessionExerciseId = 9)), emptyMap())

        assertTrue(volume.isEmpty())
    }

    @Test
    fun `no sets means no muscle groups, even for a linked exercise`() {
        val links = mapOf(1L to listOf(primary(CHEST), secondary(TRICEPS)))

        assertTrue(MuscleVolumeCalculator.volumeByMuscle(emptyList(), links).isEmpty())
    }

    @Test
    fun `a set with no load still registers its muscle groups at zero`() {
        // A bodyweight or timed set: it happened, so the muscle appears - mirroring
        // the DAO aggregate, whose COALESCE keeps the row rather than dropping it.
        val links = mapOf(1L to listOf(primary(CHEST), secondary(TRICEPS)))

        val volume = MuscleVolumeCalculator.volumeByMuscle(listOf(set(weight = null)), links)

        assertEquals(mapOf(CHEST to 0L, TRICEPS to 0L), volume)
    }

    @Test
    fun `halving truncates towards zero, exactly as the SQL aggregate does`() {
        // 3 reps x 1 mg is 3 mg; the secondary gets 1, not 1.5.
        val links = mapOf(1L to listOf(primary(CHEST), secondary(TRICEPS)))

        val volume = MuscleVolumeCalculator.volumeByMuscle(listOf(set(reps = 3, weight = 1L)), links)

        assertEquals(3L, volume[CHEST])
        assertEquals(1L, volume[TRICEPS])
    }

    @Test
    fun `each set is halved before summing, not after`() {
        // Two 3 mg sets: 1 + 1 = 2, whereas halving the 6 mg total would give 3.
        // This is what keeps the calculator bit-identical to weighted_volume, which
        // divides inside its SUM.
        val links = mapOf(1L to listOf(secondary(TRICEPS)))
        val sets = listOf(set(reps = 3, weight = 1L), set(reps = 3, weight = 1L))

        assertEquals(2L, MuscleVolumeCalculator.volumeByMuscle(sets, links)[TRICEPS])
    }

    @Test
    fun `groups come out highest volume first, ties broken by name`() {
        val links = mapOf(
            1L to listOf(primary(QUADRICEPS), secondary(GLUTES), secondary(CHEST)),
        )

        val volume = MuscleVolumeCalculator.volumeByMuscle(listOf(set()), links)

        // QUADRICEPS leads on volume; CHEST and GLUTES tie at 500 and sort by name.
        assertEquals(listOf(QUADRICEPS, CHEST, GLUTES), volume.keys.toList())
    }

    @Test
    fun `a muscle group is credited once even if a link list repeats it`() {
        // The composite primary key makes this impossible from the database; a
        // hand-assembled map should not be able to double-count either.
        val links = mapOf(1L to listOf(primary(CHEST), secondary(CHEST)))

        val volume = MuscleVolumeCalculator.volumeByMuscle(listOf(set()), links)

        assertEquals(mapOf(CHEST to 1_000 * KG), volume)
    }

    @Test
    fun `groups are keyed by id, so a stale icon does not split one muscle in two`() {
        val restyled = CHEST.copy(icon = "ic_chest_v2")
        val links = mapOf(
            1L to listOf(primary(CHEST)),
            2L to listOf(primary(restyled)),
        )
        val sets = listOf(set(sessionExerciseId = 1), set(sessionExerciseId = 2))

        val volume = MuscleVolumeCalculator.volumeByMuscle(sets, links)

        assertEquals(1, volume.size)
        assertEquals(2_000 * KG, volume.values.single())
    }

    @Test
    fun `the session exercise overload resolves links held by exercise id`() {
        val linksByExercise = mapOf(
            70L to listOf(primary(CHEST), secondary(TRICEPS)),
            71L to listOf(primary(QUADRICEPS)),
        )
        val sessionExercises = listOf(
            sessionExercise(id = 1, exerciseId = 70),
            sessionExercise(id = 2, exerciseId = 71),
        )
        val sets = listOf(set(sessionExerciseId = 1), set(sessionExerciseId = 2, weight = 140 * KG))

        val volume = MuscleVolumeCalculator.volumeByMuscle(sets, sessionExercises, linksByExercise)

        assertEquals(1_000 * KG, volume[CHEST])
        assertEquals(500 * KG, volume[TRICEPS])
        assertEquals(1_400 * KG, volume[QUADRICEPS])
    }

    @Test
    fun `the overload fans one exercise out to both of its session exercise rows`() {
        val linksByExercise = mapOf(70L to listOf(primary(CHEST), secondary(TRICEPS)))
        val sessionExercises = listOf(
            sessionExercise(id = 1, exerciseId = 70),
            sessionExercise(id = 2, exerciseId = 70),
        )
        val sets = listOf(set(sessionExerciseId = 1), set(sessionExerciseId = 2))

        val volume = MuscleVolumeCalculator.volumeByMuscle(sets, sessionExercises, linksByExercise)

        assertEquals(2_000 * KG, volume[CHEST])
        assertEquals(1_000 * KG, volume[TRICEPS])
    }

    @Test
    fun `the overload drops a session exercise whose exercise has no links`() {
        val sessionExercises = listOf(sessionExercise(id = 1, exerciseId = 70))

        val volume = MuscleVolumeCalculator.volumeByMuscle(listOf(set()), sessionExercises, emptyMap())

        assertTrue(volume.isEmpty())
    }

    @Test
    fun `primary attribution agrees with the total volume of the same sets`() {
        // A single-primary exercise attributes exactly what VolumeCalculator totals,
        // which is the invariant tying this calculator to the rest of the stats layer.
        val links = mapOf(1L to listOf(primary(CHEST)))
        val sets = List(5) { set(reps = 8, weight = 60 * KG) }

        val volume = MuscleVolumeCalculator.volumeByMuscle(sets, links)

        assertEquals(VolumeCalculator.totalVolume(sets), volume.getValue(CHEST))
    }
}
