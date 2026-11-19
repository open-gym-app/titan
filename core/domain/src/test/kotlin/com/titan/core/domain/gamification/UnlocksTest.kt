package com.titan.core.domain.gamification

import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.UnlockCondition
import com.titan.core.domain.model.UnlockProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlocksTest {

    @Test
    fun `every activity that needs a place names one that exists, and is enabled by it`() {
        TitanActivity.entries.mapNotNull { it.place }.forEach { place ->
            assertTrue(place in TitanPlace.entries)
        }
        assertEquals(listOf(TitanActivity.WALK, TitanActivity.RUN), TitanPlace.TRACK.enables)
        assertEquals(listOf(TitanActivity.SPAR), TitanPlace.RING.enables)
        assertTrue("what the sauna does is not decided yet", TitanPlace.SAUNA.enables.isEmpty())
    }

    @Test
    fun `the four starting places are owned from hatch, the other four are earned`() {
        assertEquals(setOf(TitanPlace.TRACK, TitanPlace.RACK, TitanPlace.POOL, TitanPlace.RING), TitanPlace.STARTING)
        assertEquals(TitanPlace.STARTING + TitanPlace.DOJO, TitanPlace.owned(setOf("dojo", "a_newer_unlock")))
    }

    @Test
    fun `each condition is met at its threshold and not one below`() {
        assertTrue(UnlockCondition.StreakWeeks(8).isMetBy(progress(streak = 8)))
        assertFalse(UnlockCondition.StreakWeeks(8).isMetBy(progress(streak = 7)))

        assertTrue(UnlockCondition.DistanceKm(100).isMetBy(progress(distance = 10_000_000)))
        assertFalse(UnlockCondition.DistanceKm(100).isMetBy(progress(distance = 9_999_999)))

        assertTrue(UnlockCondition.SparsWon(10).isMetBy(progress(spars = 10)))
        assertFalse(UnlockCondition.SparsWon(10).isMetBy(progress(spars = 9)))

        assertTrue(UnlockCondition.Level(100).isMetBy(progress(level = 100)))
        assertFalse(UnlockCondition.Level(100).isMetBy(progress(level = 99)))

        assertTrue(UnlockCondition.FromHatch.isMetBy(progress()))
    }

    @Test
    fun `legend rank is the summit's level`() {
        assertEquals(UnlockCondition.Level(LevelTitles.LEGEND.fromLevel), TitanPlace.SUMMIT.condition)
    }

    @Test
    fun `only earned places that are not owned from hatch are stored`() {
        assertEquals(emptySet<String>(), Unlocks.earned(progress()))
        assertEquals(
            setOf("sauna", "trail", "dojo", "summit"),
            Unlocks.earned(progress(streak = 12, distance = Long.MAX_VALUE, spars = 10, level = 120)),
        )
    }

    @Test
    fun `no two unlockables share a key`() {
        assertEquals(Unlocks.ALL.size, Unlocks.ALL.map { it.key }.toSet().size)
    }

    private fun progress(
        streak: Int = 0,
        distance: Long = 0,
        level: Int = 1,
        spars: Int = 0,
    ) = UnlockProgress(bestStreakWeeks = streak, distanceCentimetres = distance, level = level, sparsWon = spars)
}
