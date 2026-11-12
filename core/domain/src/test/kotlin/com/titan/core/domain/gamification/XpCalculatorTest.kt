package com.titan.core.domain.gamification

import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.XpEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XpCalculatorTest {

    // ─── The curve: 60 × (n − 1)^1.5 to reach level n, level 1 free ───────────────────────

    @Test
    fun `a user with no xp is at level 1`() {
        assertEquals(1, XpCalculator.levelForTotalXp(0))
    }

    @Test
    fun `a total short of the first threshold is still level 1`() {
        assertEquals(1, XpCalculator.levelForTotalXp(59))
    }

    @Test
    fun `the exact threshold buys the level`() {
        assertEquals(2, XpCalculator.levelForTotalXp(60))
    }

    @Test
    fun `a total inside a level stays at it`() {
        assertEquals(2, XpCalculator.levelForTotalXp(169))
    }

    @Test
    fun `the next threshold buys the next level`() {
        assertEquals(3, XpCalculator.levelForTotalXp(170))
    }

    @Test
    fun `the thresholds up the curve are the ones the class doc quotes`() {
        assertEquals(1_620L, XpCalculator.xpRequiredForLevel(10))
        assertEquals(59_102L, XpCalculator.xpRequiredForLevel(100))
        assertEquals(60_000L, XpCalculator.xpRequiredForLevel(101))
    }

    @Test
    fun `the curve holds far beyond where anybody will get`() {
        assertEquals(100, XpCalculator.levelForTotalXp(59_102))
        assertEquals(99, XpCalculator.levelForTotalXp(59_101))
        assertEquals(1_001, XpCalculator.levelForTotalXp(XpCalculator.xpRequiredForLevel(1_001)))
    }

    /**
     * The ceiling readers clamp a stored total to (#256): the curve has to price it, and the
     * level above it, without leaving an `Int`.
     */
    @Test
    fun `the curve prices every total up to the ceiling`() {
        val level = XpCalculator.levelForTotalXp(XpCalculator.MAX_TOTAL_XP)

        assertEquals(XpCalculator.MAX_LEVEL, level)
        assertTrue(XpCalculator.xpRequiredForLevel(level) <= XpCalculator.MAX_TOTAL_XP)
        assertTrue(XpCalculator.xpToNextLevel(level) > 0)
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
        val widths = (1..500).map(XpCalculator::xpToNextLevel)

        assertEquals(emptyList<Pair<Int, Int>>(), widths.zipWithNext().filter { (lower, higher) -> higher <= lower })
    }

    @Test
    fun `a level's width is the gap between its threshold and the next`() {
        assertEquals(60, XpCalculator.xpToNextLevel(1))
        assertEquals(110, XpCalculator.xpToNextLevel(2))
        assertEquals(898, XpCalculator.xpToNextLevel(100))
    }

    @Test
    fun `the requirement for a level is the lowest total that buys it`() {
        for (level in 1..1_000) {
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
