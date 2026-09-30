package com.titan.core.domain.gamification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The rank table: what it contains, and that every level maps into it.
 *
 * Walks [LevelTitles.ALL] rather than naming seven ranks, the same call [AchievementsTest] and
 * [QuestsTest] make.
 */
class LevelTitlesTest {

    @Test
    fun `the initial table ships seven ranks`() {
        assertEquals(7, LevelTitles.ALL.size)
    }

    @Test
    fun `no two ranks share an id`() {
        assertEquals(LevelTitles.ALL.size, LevelTitles.ALL.map(LevelTitle::id).toSet().size)
    }

    @Test
    fun `no two ranks open on the same level`() {
        // Two bands opening together would make one of them unreachable, and which one survives
        // would be an accident of list order.
        assertEquals(LevelTitles.ALL.size, LevelTitles.ALL.map(LevelTitle::fromLevel).toSet().size)
    }

    @Test
    fun `the ranks are listed in the order they are earned`() {
        val levels = LevelTitles.ALL.map(LevelTitle::fromLevel)
        assertEquals(levels.sorted(), levels)
    }

    @Test
    fun `the table opens on the first level, so no level is below it`() {
        assertEquals(XpCalculator.FIRST_LEVEL, LevelTitles.ALL.first().fromLevel)
    }

    @Test
    fun `a rank cannot open below the first level`() {
        assertThrows(IllegalArgumentException::class.java) {
            LevelTitle(id = "prenatal", fromLevel = XpCalculator.FIRST_LEVEL - 1)
        }
    }

    @Test
    fun `each rank is held on the level it opens at`() {
        LevelTitles.ALL.forEach { rank ->
            assertEquals("${rank.id} is not held at its own level", rank, LevelTitles.forLevel(rank.fromLevel))
        }
    }

    @Test
    fun `the rank below is still held one level short`() {
        LevelTitles.ALL.zipWithNext { lower, higher ->
            assertEquals(
                "${higher.id} was awarded a level early",
                lower,
                LevelTitles.forLevel(higher.fromLevel - 1),
            )
        }
    }

    @Test
    fun `a level past the end of the table keeps the last rank`() {
        // #104 asks for exactly this. The alternative is a blank where a user who has
        // out-levelled the table should see the highest rank there is.
        val last = LevelTitles.ALL.last()

        assertEquals(last, LevelTitles.forLevel(last.fromLevel + 1))
        assertEquals(last, LevelTitles.forLevel(last.fromLevel + 1_000))
    }

    @Test
    fun `every level from the first to well past the table has a rank`() {
        (XpCalculator.FIRST_LEVEL..200).forEach { level ->
            val rank = LevelTitles.forLevel(level)
            assert(rank in LevelTitles.ALL) { "level $level mapped outside the table" }
        }
    }

    @Test
    fun `a level below the first returns the opening rank rather than throwing`() {
        // Not reachable from a valid row — the curve's floor is the table's floor — but a corrupt
        // row should cost a user their rank and not their screen.
        assertEquals(LevelTitles.ALL.first(), LevelTitles.forLevel(0))
        assertEquals(LevelTitles.ALL.first(), LevelTitles.forLevel(-5))
    }
}
