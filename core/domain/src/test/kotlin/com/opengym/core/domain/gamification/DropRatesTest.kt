package com.opengym.core.domain.gamification

import com.opengym.core.domain.model.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The economy's one tuning table, held to the shape its documentation claims.
 *
 * Relationships rather than figures, the call [QuestsTest] makes about quest prices: a tuning
 * pass should be free to move the numbers and should not be free to invert the curve, price a
 * band out of reach, or leave a rarity unpriced.
 */
class DropRatesTest {

    @Test
    fun `every band is priced`() {
        // A rarity the map does not name is a band the roll can never land on: [Rarity] is what
        // the evaluator walks, and an unpriced constant would be an unobtainable collectible for
        // everyone stocked in it.
        assertEquals(Rarity.entries.toSet(), DropRates.WEIGHTS.keys)
    }

    @Test
    fun `the bands are laid out commonest first`() {
        // Load-bearing, not cosmetic: the evaluator lays the bands end to end in iteration order,
        // so this order *is* the boundary between one band and the next.
        assertEquals(Rarity.entries, DropRates.WEIGHTS.keys.toList())
    }

    @Test
    fun `a band nobody can roll is not a band`() {
        DropRates.WEIGHTS.forEach { (rarity, weight) ->
            assertTrue("$rarity is priced at $weight and can never drop", weight > 0)
        }
    }

    @Test
    fun `a rarer band is rarer`() {
        // The curve, asserted rather than left to the reading eye. Equal weights would make two
        // bands the same thing wearing different names.
        DropRates.WEIGHTS.values.zipWithNext { commoner, rarer ->
            assertTrue("$rarer is priced at or above $commoner", rarer < commoner)
        }
    }

    @Test
    fun `the bands fit inside the roll`() {
        // Past [DropRates.TOTAL_WEIGHT] the last bands sit in a range no roll reaches, and the
        // rarest collectibles become unobtainable without a single number looking wrong.
        assertTrue(
            "the bands sum to ${DropRates.DROP_WEIGHT}, past a roll of ${DropRates.TOTAL_WEIGHT}",
            DropRates.DROP_WEIGHT <= DropRates.TOTAL_WEIGHT,
        )
    }

    @Test
    fun `the drop weight is the sum of the bands`() {
        assertEquals(DropRates.WEIGHTS.values.sum(), DropRates.DROP_WEIGHT)
    }

    @Test
    fun `dropping is the exception rather than the rule`() {
        // The remainder is the no-drop case, and it has to stay the bigger half: loot that
        // arrives more often than not is a per-session reward with a random name on it, and the
        // rarity ladder above it stops meaning anything.
        assertTrue("every trigger drops something", DropRates.DROP_WEIGHT > 0)
        assertTrue(
            "${DropRates.DROP_WEIGHT} of ${DropRates.TOTAL_WEIGHT} triggers pay out",
            DropRates.DROP_WEIGHT * 2 < DropRates.TOTAL_WEIGHT,
        )
    }
}
