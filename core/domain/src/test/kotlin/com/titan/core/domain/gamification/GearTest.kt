package com.titan.core.domain.gamification

import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.GearSlot
import com.titan.core.domain.model.UnlockCondition
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
    fun `a titan hatches with no gear`() {
        assertTrue(Gear.entries.none { it.isOwned(dropped = emptySet(), achievements = emptySet()) })
    }

    @Test
    fun `three pieces come with an achievement, the rest drop on a level-up`() {
        assertEquals(UnlockCondition.Achievement("first_session"), Gear.HEADBAND.condition)
        assertEquals(UnlockCondition.Achievement("ten_sessions"), Gear.SHADES.condition)
        assertEquals(UnlockCondition.Achievement("first_record"), Gear.CROWN.condition)
        assertEquals(
            listOf(Gear.CHALK, Gear.CAP, Gear.CAPE, Gear.SCARF, Gear.MEDAL),
            Gear.entries.filter { it.condition == UnlockCondition.LevelUpDrop },
        )
    }

    @Test
    fun `an achievement piece is owned while the achievement is held, a dropped one once stored`() {
        assertTrue(Gear.HEADBAND.isOwned(dropped = emptySet(), achievements = setOf("first_session")))
        assertFalse(Gear.HEADBAND.isOwned(dropped = setOf("headband"), achievements = emptySet()))
        assertTrue(Gear.CAPE.isOwned(dropped = setOf("cape"), achievements = emptySet()))
        assertFalse(Gear.CAPE.isOwned(dropped = emptySet(), achievements = setOf("first_session")))
    }

    @Test
    fun `gear joins the unlocks, with no key shared with a place`() {
        assertEquals(Unlocks.ALL.size, Unlocks.ALL.map { it.key }.toSet().size)
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
