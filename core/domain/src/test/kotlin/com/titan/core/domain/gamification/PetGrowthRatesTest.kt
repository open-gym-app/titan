package com.titan.core.domain.gamification

import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PetGain
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PetGrowthRatesTest {

    @Test
    fun `a session of lifts pays session xp, xp per set and strength`() {
        val gain = PetGrowthRates.gainFor(List(3) { lift() })

        assertEquals(
            PetGain(
                xp = PetGrowthRates.XP_PER_SESSION + 3 * PetGrowthRates.XP_PER_SET,
                stats = PetStats(strength = 3, endurance = 0, mobility = 0),
            ),
            gain,
        )
    }

    @Test
    fun `a session of timed holds grows mobility and no strength`() {
        val gain = PetGrowthRates.gainFor(List(4) { hold() })

        assertEquals(PetStats(strength = 0, endurance = 0, mobility = 4), gain.stats)
    }

    @Test
    fun `a mixed session splits its stats by set`() {
        val gain = PetGrowthRates.gainFor(listOf(lift(), run(), run(), hold()))

        assertEquals(PetStats(strength = 1, endurance = 2, mobility = 1), gain.stats)
    }

    @Test
    fun `warm-ups and uncompleted sets pay nothing`() {
        val gain = PetGrowthRates.gainFor(
            listOf(lift(SetType.WARMUP), lift(SetType.WARMUP), lift(completed = false), lift()),
        )

        // One paid set: under the minimum for session xp, and one strength.
        assertEquals(PetGain(xp = PetGrowthRates.XP_PER_SET, stats = PetStats(1, 0, 0)), gain)
    }

    @Test
    fun `fewer paid sets than the minimum pay no session xp`() {
        val justUnder = PetGrowthRates.gainFor(List(SessionXp.MIN_SETS_FOR_SESSION_XP - 1) { lift() })
        val atIt = PetGrowthRates.gainFor(List(SessionXp.MIN_SETS_FOR_SESSION_XP) { lift() })

        assertEquals((SessionXp.MIN_SETS_FOR_SESSION_XP - 1) * PetGrowthRates.XP_PER_SET, justUnder.xp)
        assertEquals(
            PetGrowthRates.XP_PER_SESSION + SessionXp.MIN_SETS_FOR_SESSION_XP * PetGrowthRates.XP_PER_SET,
            atIt.xp,
        )
    }

    @Test
    fun `sets beyond the cap pay nothing, and the first ones are the ones counted`() {
        val sets = List(SessionXp.MAX_PAID_SETS) { lift() } + List(10) { hold() }

        val gain = PetGrowthRates.gainFor(sets)

        assertEquals(
            PetGrowthRates.XP_PER_SESSION + SessionXp.MAX_PAID_SETS * PetGrowthRates.XP_PER_SET,
            gain.xp,
        )
        assertEquals(PetStats(strength = SessionXp.MAX_PAID_SETS, endurance = 0, mobility = 0), gain.stats)
    }

    @Test
    fun `a session with no paid set is worth nothing`() {
        assertTrue(PetGrowthRates.gainFor(emptyList()).isNothing)
        assertTrue(PetGrowthRates.gainFor(listOf(lift(SetType.WARMUP))).isNothing)
    }

    @Test
    fun `a gain cannot take xp away`() {
        assertThrows(IllegalArgumentException::class.java) { PetGain(xp = -1, stats = PetStats.ZERO) }
    }

    private fun lift(
        type: SetType = SetType.NORMAL,
        completed: Boolean = true,
    ) = set(reps = 5, setType = type, completed = completed) to ExerciseType.STRENGTH

    private fun run() = set(distance = 100_000) to ExerciseType.ENDURANCE

    private fun hold() = set(duration = 60) to ExerciseType.ISOMETRIC

    private fun set(
        reps: Int? = null,
        duration: Long? = null,
        distance: Long? = null,
        setType: SetType = SetType.NORMAL,
        completed: Boolean = true,
    ) = WorkoutSet(
        sessionExerciseId = 1,
        setNumber = 1,
        reps = reps,
        duration = duration,
        distance = distance,
        setType = setType,
        isCompleted = completed,
        completedAt = 0,
        createdAt = 0,
        updatedAt = 0,
    )
}
