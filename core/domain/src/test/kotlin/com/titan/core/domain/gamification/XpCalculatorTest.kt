package com.titan.core.domain.gamification

import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.XpEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XpCalculatorTest {

    // ─── The curve: 600 XP a level, level 1 free ────────────────────────────────────────

    @Test
    fun `a user with no xp is at level 1`() {
        assertEquals(1, XpCalculator.levelForTotalXp(0))
    }

    @Test
    fun `a total short of the first threshold is still level 1`() {
        assertEquals(1, XpCalculator.levelForTotalXp(599))
    }

    @Test
    fun `the exact threshold buys the level`() {
        assertEquals(2, XpCalculator.levelForTotalXp(600))
    }

    @Test
    fun `a total inside a level stays at it`() {
        assertEquals(2, XpCalculator.levelForTotalXp(1_199))
    }

    @Test
    fun `the next threshold buys the next level`() {
        assertEquals(3, XpCalculator.levelForTotalXp(1_200))
    }

    @Test
    fun `the curve stays linear well up it`() {
        assertEquals(11, XpCalculator.levelForTotalXp(6_000))
        assertEquals(43, XpCalculator.levelForTotalXp(25_437))
        assertEquals(1_001, XpCalculator.levelForTotalXp(600_000))
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
    fun `each level costs the same as the last`() {
        val costs = (1..6).map(XpCalculator::xpRequiredForLevel)
        val steps = costs.zipWithNext { lower, higher -> higher - lower }

        assertEquals(listOf(600L, 600L, 600L, 600L, 600L), steps)
    }

    @Test
    fun `the requirement for a level is the lowest total that buys it`() {
        for (level in 1..20) {
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

    // ─── Pricing: one named constant per source ─────────────────────────────────────────

    @Test
    fun `a logged set is worth its own constant`() {
        assertEquals(XpCalculator.XP_PER_SET, XpCalculator.xpFor(XpEvent.SetLogged))
    }

    @Test
    fun `a finished session is worth its own constant`() {
        assertEquals(XpCalculator.XP_PER_SESSION, XpCalculator.xpFor(XpEvent.SessionFinished))
    }

    @Test
    fun `a broken record is worth its own constant, whichever type fell`() {
        for (type in PrType.entries) {
            assertEquals(XpCalculator.XP_PER_PR, XpCalculator.xpFor(XpEvent.PrBroken(type)))
        }
    }

    @Test
    fun `an unlocked achievement is worth its own constant`() {
        assertEquals(
            XpCalculator.XP_PER_ACHIEVEMENT,
            XpCalculator.xpFor(XpEvent.AchievementUnlocked("first_session")),
        )
    }

    @Test
    fun `every source is worth something`() {
        val events = listOf(
            XpEvent.SetLogged,
            XpEvent.SessionFinished,
            XpEvent.PrBroken(PrType.MAX_WEIGHT),
            XpEvent.AchievementUnlocked("first_pr"),
        )

        assertEquals(emptyList<XpEvent>(), events.filter { XpCalculator.xpFor(it) <= 0 })
    }
}
