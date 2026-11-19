package com.titan.core.domain.gamification

import com.titan.core.domain.model.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hatch table, held to the shape its documentation claims. */
class HatchRatesTest {

    @Test
    fun `every band is priced, commonest first`() {
        assertEquals(Rarity.entries, HatchRates.WEIGHTS.keys.toList())
    }

    @Test
    fun `the weights sum to their declared total`() {
        // Unlike a drop, a hatch has no "nothing" share: a sum short of the total is a roll with
        // no species behind it.
        assertEquals(HatchRates.TOTAL_WEIGHT, HatchRates.WEIGHTS.values.sum())
    }

    @Test
    fun `a rarer band is rarer, and none is unreachable`() {
        HatchRates.WEIGHTS.values.forEach { weight -> assertTrue(weight > 0) }
        HatchRates.WEIGHTS.values.zipWithNext { commoner, rarer ->
            assertTrue("$rarer is priced at or above $commoner", rarer < commoner)
        }
    }

    @Test
    fun `a roll lands in its band at each boundary`() {
        assertEquals(Rarity.COMMON, HatchRates.rarityFor(0))
        assertEquals(Rarity.COMMON, HatchRates.rarityFor(49))
        assertEquals(Rarity.UNCOMMON, HatchRates.rarityFor(50))
        assertEquals(Rarity.UNCOMMON, HatchRates.rarityFor(77))
        assertEquals(Rarity.RARE, HatchRates.rarityFor(78))
        assertEquals(Rarity.RARE, HatchRates.rarityFor(92))
        assertEquals(Rarity.EPIC, HatchRates.rarityFor(93))
        assertEquals(Rarity.EPIC, HatchRates.rarityFor(98))
        assertEquals(Rarity.LEGENDARY, HatchRates.rarityFor(99))
    }

    @Test
    fun `a roll outside the table is refused`() {
        assertThrows(IllegalArgumentException::class.java) { HatchRates.rarityFor(-1) }
        assertThrows(IllegalArgumentException::class.java) { HatchRates.rarityFor(HatchRates.TOTAL_WEIGHT) }
    }
}
