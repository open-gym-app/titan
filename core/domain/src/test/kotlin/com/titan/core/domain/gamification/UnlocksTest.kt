package com.titan.core.domain.gamification

import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.UnlockCondition
import com.titan.core.domain.random.RandomSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlocksTest {

    @Test
    fun `a titan hatches with the four base places`() {
        assertEquals(
            setOf(TitanPlace.TRACK, TitanPlace.RACK, TitanPlace.POOL, TitanPlace.RING),
            TitanPlace.owned(dropped = emptySet(), achievements = emptySet()),
        )
        assertEquals(TitanPlace.STARTING, TitanPlace.owned(dropped = emptySet(), achievements = emptySet()))
    }

    @Test
    fun `the other places come with an achievement or a level-up`() {
        assertEquals(UnlockCondition.Achievement("four_week_streak"), TitanPlace.SAUNA.condition)
        assertEquals(UnlockCondition.Achievement("twelve_week_streak"), TitanPlace.TRAIL.condition)
        assertEquals(UnlockCondition.Achievement("hundred_sessions"), TitanPlace.SUMMIT.condition)
        assertEquals(UnlockCondition.LevelUpDrop, TitanPlace.DOJO.condition)
        assertTrue(TitanPlace.SAUNA in TitanPlace.owned(dropped = emptySet(), achievements = setOf("four_week_streak")))
        assertTrue(TitanPlace.DOJO in TitanPlace.owned(dropped = setOf("dojo"), achievements = emptySet()))
    }

    @Test
    fun `the level-up pool is every drop-only place and piece of gear`() {
        assertEquals(listOf("dojo", "chalk", "cap", "cape", "scarf", "medal"), Unlocks.LEVEL_UP_POOL)
    }

    @Test
    fun `each level gained drops one item, never one already dropped`() {
        val first = RandomSource { 0 }

        assertEquals(setOf("dojo"), Unlocks.levelUpDrops(levelsGained = 1, dropped = emptySet(), random = first))
        assertEquals(
            setOf("chalk", "cap"),
            Unlocks.levelUpDrops(levelsGained = 2, dropped = setOf("dojo"), random = first),
        )
    }

    @Test
    fun `the draw stops when the pool is empty, and no level drops nothing`() {
        val all = Unlocks.LEVEL_UP_POOL.toSet()

        assertEquals(
            all,
            Unlocks.levelUpDrops(levelsGained = 50, dropped = emptySet(), random = RandomSource { it - 1 }),
        )
        assertEquals(
            emptySet<String>(),
            Unlocks.levelUpDrops(levelsGained = 3, dropped = all, random = RandomSource { 0 }),
        )
        assertEquals(
            emptySet<String>(),
            Unlocks.levelUpDrops(levelsGained = 0, dropped = emptySet(), random = RandomSource { 0 }),
        )
    }

    @Test
    fun `no two unlockables share a key`() {
        assertEquals(Unlocks.ALL.size, Unlocks.ALL.map { it.key }.toSet().size)
    }
}
