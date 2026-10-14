package com.titan.core.domain.gamification

import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.XpEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XpCalculatorTest {

    // ─── The curve: 100 × (level − 1)^1.5, level 1 free ─────────────────────────────────

    @Test
    fun `a user with no xp is at level 1`() {
        assertEquals(1, XpCalculator.levelForTotalXp(0))
    }

    @Test
    fun `a total short of the first threshold is still level 1`() {
        assertEquals(1, XpCalculator.levelForTotalXp(99))
    }

    @Test
    fun `the exact threshold buys the level`() {
        assertEquals(2, XpCalculator.levelForTotalXp(100))
    }

    @Test
    fun `a total inside a level stays at it`() {
        assertEquals(2, XpCalculator.levelForTotalXp(282))
    }

    @Test
    fun `the next threshold buys the next level`() {
        assertEquals(3, XpCalculator.levelForTotalXp(283))
    }

    @Test
    fun `the curve passes through the figures the docs promise`() {
        assertEquals(
            mapOf(2 to 100L, 3 to 283L, 4 to 520L, 5 to 800L, 10 to 2_700L, 20 to 8_282L, 50 to 34_300L),
            listOf(2, 3, 4, 5, 10, 20, 50).associateWith(XpCalculator::xpRequiredForLevel),
        )
    }

    @Test
    fun `levels come from the curve well up it`() {
        assertEquals(5, XpCalculator.levelForTotalXp(800))
        assertEquals(4, XpCalculator.levelForTotalXp(799))
        assertEquals(10, XpCalculator.levelForTotalXp(2_700))
        assertEquals(50, XpCalculator.levelForTotalXp(34_300))
        assertEquals(49, XpCalculator.levelForTotalXp(34_299))
    }

    @Test
    fun `a total beyond any level that can be reached stops at the last one`() {
        assertEquals(XpCalculator.MAX_LEVEL, XpCalculator.levelForTotalXp(Long.MAX_VALUE))
    }

    @Test
    fun `a negative total is a corrupt row, not a level`() {
        assertThrows(IllegalArgumentException::class.java) { XpCalculator.levelForTotalXp(-1) }
    }

    // ─── The inverse ────────────────────────────────────────────────────────────────────

    @Test
    fun `level 1 costs nothing`() {
        assertEquals(0L, XpCalculator.xpRequiredForLevel(1))
    }

    @Test
    fun `each level costs more than the last`() {
        val costs = (1..200).map(XpCalculator::xpToNextLevel)

        assertTrue(costs.zipWithNext().all { (lower, higher) -> higher > lower })
    }

    @Test
    fun `the first level costs what the scale says`() {
        assertEquals(100, XpCalculator.xpToNextLevel(1))
    }

    @Test
    fun `the cost of a level is the gap between its two thresholds`() {
        assertEquals(183, XpCalculator.xpToNextLevel(2))
        assertEquals(237, XpCalculator.xpToNextLevel(3))
    }

    @Test
    fun `the requirement for a level is the lowest total that buys it`() {
        for (level in 1..MAX_CHECKED_LEVEL) {
            val required = XpCalculator.xpRequiredForLevel(level)

            assertEquals(level, XpCalculator.levelForTotalXp(required))
            if (level > 1) {
                assertEquals(level - 1, XpCalculator.levelForTotalXp(required - 1))
            }
        }
    }

    @Test
    fun `there is no level below the first`() {
        assertThrows(IllegalArgumentException::class.java) { XpCalculator.xpRequiredForLevel(0) }
    }

    // ─── Pricing: one answer per source ─────────────────────────────────────────────────

    @Test
    fun `a working set is worth its own constant`() {
        assertEquals(XpCalculator.XP_PER_SET, XpCalculator.xpFor(XpEvent.SetLogged))
    }

    @Test
    fun `a finished session is worth its own constant`() {
        assertEquals(XpCalculator.XP_PER_SESSION, XpCalculator.xpFor(XpEvent.SessionFinished))
    }

    @Test
    fun `a broken record is worth what its type says`() {
        for (type in PrType.entries) {
            assertEquals(XpCalculator.xpForRecord(type), XpCalculator.xpFor(XpEvent.PrBroken(type)))
        }
    }

    @Test
    fun `the headline records of a discipline pay more than the ones that follow them`() {
        assertEquals(20, XpCalculator.xpForRecord(PrType.MAX_WEIGHT))
        assertEquals(20, XpCalculator.xpForRecord(PrType.ESTIMATED_1RM))
        assertEquals(15, XpCalculator.xpForRecord(PrType.BEST_PACE))
        assertEquals(10, XpCalculator.xpForRecord(PrType.MAX_REPS))
        assertEquals(10, XpCalculator.xpForRecord(PrType.MAX_VOLUME))
    }

    @Test
    fun `an unlocked achievement is worth what its rung says`() {
        for (tier in AchievementTier.entries) {
            assertEquals(
                XpCalculator.xpForAchievement(tier),
                XpCalculator.xpFor(XpEvent.AchievementUnlocked("first_session", tier)),
            )
        }
    }

    @Test
    fun `a harder rung pays more than an easier one`() {
        val prices = AchievementTier.entries.map(XpCalculator::xpForAchievement)

        assertEquals(prices.sorted(), prices)
        assertEquals(prices.distinct(), prices)
    }

    @Test
    fun `a quest is worth what it says it is`() {
        assertEquals(75, XpCalculator.xpFor(XpEvent.QuestCompleted("weekly_sessions", xp = 75)))
    }

    @Test
    fun `every source is worth something`() {
        val events = listOf(
            XpEvent.SetLogged,
            XpEvent.SessionFinished,
            XpEvent.PrBroken(PrType.MAX_WEIGHT),
            XpEvent.AchievementUnlocked("first_pr", AchievementTier.BRONZE),
        )

        assertEquals(emptyList<XpEvent>(), events.filter { XpCalculator.xpFor(it) <= 0 })
    }

    // ─── The economy's shape ────────────────────────────────────────────────────────────

    @Test
    fun `a session is worth more than a set`() {
        assertTrue(XpCalculator.XP_PER_SESSION > XpCalculator.XP_PER_SET)
    }

    @Test
    fun `the most one session can pay for showing up is still short of level 3`() {
        val ceiling = XpCalculator.XP_PER_SESSION + SessionXp.MAX_PAID_SETS * XpCalculator.XP_PER_SET

        assertTrue("$ceiling XP in one session", ceiling < XpCalculator.xpRequiredForLevel(3))
    }

    private companion object {
        const val MAX_CHECKED_LEVEL = 300
    }
}
