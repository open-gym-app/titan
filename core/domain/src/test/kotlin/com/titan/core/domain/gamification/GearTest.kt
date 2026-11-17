package com.titan.core.domain.gamification

import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.GearSlot
import com.titan.core.domain.model.UnlockCondition
import com.titan.core.domain.model.UnlockProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearTest {

    @Test
    fun `the gear and slot keys are the ones a loadout row stores`() {
        assertEquals(
            listOf("headband", "shades", "crown", "chalk", "cap", "cape", "scarf", "medal"),
            Gear.entries.map { it.key },
        )
        assertEquals(listOf("head", "face", "hands", "neck", "back", "chest"), GearSlot.entries.map { it.key })
    }

    @Test
    fun `headband, shades and crown are owned from hatch, the rest are earned`() {
        assertEquals(
            listOf(Gear.HEADBAND, Gear.SHADES, Gear.CROWN),
            Gear.entries.filter { it.isOwned(unlocked = emptySet()) },
        )
        assertTrue(Gear.CAPE.isOwned(setOf("cape")))
    }

    @Test
    fun `each piece unlocks on the canvas's condition`() {
        assertEquals(UnlockCondition.Level(20), Gear.CHALK.condition)
        assertEquals(UnlockCondition.DistanceKm(100), Gear.CAP.condition)
        assertEquals(UnlockCondition.Level(40), Gear.CAPE.condition)
        assertEquals(UnlockCondition.SparsWon(10), Gear.SCARF.condition)
        assertEquals(UnlockCondition.Level(LevelTitles.LEGEND.fromLevel), Gear.MEDAL.condition)
    }

    @Test
    fun `gear joins the unlocks, with no key shared with a place`() {
        assertEquals(Unlocks.ALL.size, Unlocks.ALL.map { it.key }.toSet().size)
        assertTrue(
            "chalk" in Unlocks.earned(
                UnlockProgress(bestStreakWeeks = 0, distanceCentimetres = 0, level = 20, sparsWon = 0),
            ),
        )
    }

    @Test
    fun `the canvas draws the headband and crown on the head and the shades on the face`() {
        assertEquals(GearSlot.HEAD, Gear.HEADBAND.slot)
        assertEquals(GearSlot.HEAD, Gear.CROWN.slot)
        assertEquals(GearSlot.FACE, Gear.SHADES.slot)
    }

    @Test
    fun `an unknown key is no gear`() {
        assertEquals(Gear.CAPE, Gear.byKey("cape"))
        assertNull(Gear.byKey("jetpack"))
        assertFalse(Gear.entries.any { it.key == "jetpack" })
    }
}
