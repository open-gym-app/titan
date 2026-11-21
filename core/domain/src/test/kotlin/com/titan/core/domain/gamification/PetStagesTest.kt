package com.titan.core.domain.gamification

import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetStage
import com.titan.core.domain.model.PetStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class PetStagesTest {

    @Test
    fun `the stage table is the documented one`() {
        assertEquals(
            listOf(
                PetStage.HATCHLING to 1,
                PetStage.JUVENILE to 10,
                PetStage.ADULT to 25,
                PetStage.TITAN to 50,
            ),
            PetStage.entries.map { it to it.fromLevel },
        )
    }

    @Test
    fun `a stage starts at its threshold and not one level before`() {
        assertEquals(PetStage.HATCHLING, PetStages.stageForLevel(1))
        assertEquals(PetStage.HATCHLING, PetStages.stageForLevel(2))

        assertEquals(PetStage.HATCHLING, PetStages.stageForLevel(9))
        assertEquals(PetStage.JUVENILE, PetStages.stageForLevel(10))
        assertEquals(PetStage.JUVENILE, PetStages.stageForLevel(11))

        assertEquals(PetStage.JUVENILE, PetStages.stageForLevel(24))
        assertEquals(PetStage.ADULT, PetStages.stageForLevel(25))
        assertEquals(PetStage.ADULT, PetStages.stageForLevel(26))

        assertEquals(PetStage.ADULT, PetStages.stageForLevel(49))
        assertEquals(PetStage.TITAN, PetStages.stageForLevel(50))
        assertEquals(PetStage.TITAN, PetStages.stageForLevel(51))
    }

    @Test
    fun `a level below the first threshold is the first stage`() {
        assertEquals(PetStage.HATCHLING, PetStages.stageForLevel(0))
        assertEquals(PetStage.HATCHLING, PetStages.stageForLevel(-7))
    }

    @Test
    fun `each stage names the one after it, and the last names none`() {
        assertEquals(PetStage.JUVENILE, PetStages.next(PetStage.HATCHLING))
        assertEquals(PetStage.TITAN, PetStages.next(PetStage.ADULT))
        assertNull(PetStages.next(PetStage.TITAN))
    }

    @Test
    fun `a pet's level is the user's curve over the pet's own xp`() {
        assertEquals(1, pet(xp = 0).level)
        assertEquals(PetStage.HATCHLING, pet(xp = 0).stage)

        val juvenileAt = XpCalculator.xpRequiredForLevel(PetStage.JUVENILE.fromLevel)
        assertEquals(9, pet(xp = juvenileAt - 1).level)
        assertEquals(PetStage.HATCHLING, pet(xp = juvenileAt - 1).stage)
        assertEquals(10, pet(xp = juvenileAt).level)
        assertEquals(PetStage.JUVENILE, pet(xp = juvenileAt).stage)
    }

    @Test
    fun `a pet cannot hold negative xp`() {
        assertThrows(IllegalArgumentException::class.java) { pet(xp = -1) }
    }

    private fun pet(xp: Long) =
        Pet(
            id = 1,
            speciesId = PetSpeciesCatalogue.IRON_CUB.id,
            hatchedAt = Instant.EPOCH,
            xp = xp,
            stats = PetStats.ZERO,
            nickname = null,
            isActive = true,
        )
}
