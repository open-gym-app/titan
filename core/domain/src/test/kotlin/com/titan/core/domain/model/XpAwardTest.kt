package com.titan.core.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XpAward.leveledUp] on its own, over awards the use case cannot currently produce.
 *
 * `AwardXpUseCaseTest` covers what today's constants can reach; this pins what the property
 * promises for the awards a retune would make reachable — an award worth more than a level,
 * which crosses two thresholds and must still read as a single level-up.
 */
class XpAwardTest {

    @Test
    fun `an award that gained no level did not level up`() {
        val award = XpAward(awarded = 5, totalXp = 95, previousLevel = 1, level = 1)

        assertFalse(award.leveledUp)
    }

    @Test
    fun `an award that clears two thresholds is still one level-up`() {
        val award = XpAward(awarded = 250, totalXp = 445, previousLevel = 2, level = 4)

        assertTrue(award.leveledUp)
    }
}
