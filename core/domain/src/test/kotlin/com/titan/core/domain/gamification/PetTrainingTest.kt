package com.titan.core.domain.gamification

import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PetStat
import com.titan.core.domain.model.PetStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PetTrainingTest {

    @Test
    fun `each kind of exercise grows its own stat`() {
        assertEquals(PetStat.STRENGTH, PetTraining.statFor(ExerciseType.STRENGTH))
        assertEquals(PetStat.ENDURANCE, PetTraining.statFor(ExerciseType.ENDURANCE))
        assertEquals(PetStat.MOBILITY, PetTraining.statFor(ExerciseType.ISOMETRIC))
    }

    @Test
    fun `no stat is left without an exercise that grows it`() {
        // The other direction of the mapping: a stat nothing feeds is a bar that never moves.
        assertEquals(PetStat.entries.toSet(), ExerciseType.entries.map(PetTraining::statFor).toSet())
    }

    @Test
    fun `stats read back by name`() {
        val stats = PetStats(strength = 3, endurance = 5, mobility = 8)

        assertEquals(3, stats[PetStat.STRENGTH])
        assertEquals(5, stats[PetStat.ENDURANCE])
        assertEquals(8, stats[PetStat.MOBILITY])
    }

    @Test
    fun `growing one stat leaves the other two alone`() {
        val stats = PetStats(strength = 3, endurance = 5, mobility = 8)

        assertEquals(PetStats(4, 5, 8), stats.plus(PetStat.STRENGTH, 1))
        assertEquals(PetStats(3, 7, 8), stats.plus(PetStat.ENDURANCE, 2))
        assertEquals(PetStats(3, 5, 8), stats.plus(PetStat.MOBILITY, 0))
        assertEquals(PetStats(3, 5, 11), stats.plus(PetStat.MOBILITY, 3))
    }

    @Test
    fun `a stat stops at its bound rather than overflowing`() {
        val nearlyFull = PetStats(strength = PetStats.MAX - 1, endurance = 0, mobility = 0)

        assertEquals(PetStats.MAX, nearlyFull.plus(PetStat.STRENGTH, 1).strength)
        assertEquals(PetStats.MAX, nearlyFull.plus(PetStat.STRENGTH, Int.MAX_VALUE).strength)
    }

    @Test
    fun `a stat is never taken away`() {
        assertThrows(IllegalArgumentException::class.java) { PetStats.ZERO.plus(PetStat.STRENGTH, -1) }
    }

    @Test
    fun `a stat outside its range is refused, one field at a time`() {
        assertThrows(IllegalArgumentException::class.java) { PetStats(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { PetStats(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { PetStats(0, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { PetStats(PetStats.MAX + 1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { PetStats(0, PetStats.MAX + 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { PetStats(0, 0, PetStats.MAX + 1) }
    }

    @Test
    fun `unchecked values are clamped into range`() {
        assertEquals(
            PetStats(strength = 0, endurance = PetStats.MAX, mobility = 12),
            PetStats.clamped(strength = Int.MIN_VALUE, endurance = Int.MAX_VALUE, mobility = 12),
        )
    }
}
