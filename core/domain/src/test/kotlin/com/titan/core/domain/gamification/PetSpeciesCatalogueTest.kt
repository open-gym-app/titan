package com.titan.core.domain.gamification

import com.titan.core.domain.model.PetSpecies
import com.titan.core.domain.model.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The species catalogue as data, held the way [CollectiblesTest] holds the collectibles. */
class PetSpeciesCatalogueTest {

    @Test
    fun `the ids are the ones a pet row holds`() {
        // Pinned: an id is written to the pet table, and a rename orphans every pet already
        // hatched under the old spelling. A failure here means the change needs a migration.
        assertEquals(
            setOf("iron_cub", "chalk_sprite", "plate_tortoise", "kettle_ram", "barbell_drake", "atlas"),
            PetSpeciesCatalogue.ALL.map(PetSpecies::id).toSet(),
        )
    }

    @Test
    fun `no two species share an id`() {
        assertEquals(PetSpeciesCatalogue.ALL.size, PetSpeciesCatalogue.ALL.map(PetSpecies::id).toSet().size)
    }

    @Test
    fun `an id is spelled the way every other stored key in this app is`() {
        val key = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")
        PetSpeciesCatalogue.ALL.forEach { species ->
            assertTrue("${species.id} is not a lower snake case key", key.matches(species.id))
        }
    }

    @Test
    fun `every species is reachable by its own id`() {
        PetSpeciesCatalogue.ALL.forEach { species ->
            assertEquals(species, PetSpeciesCatalogue.byId(species.id))
        }
    }

    @Test
    fun `an id this build does not know reads back as nothing rather than throwing`() {
        assertNull(PetSpeciesCatalogue.byId("a_species_from_the_future"))
    }

    @Test
    fun `species are listed commonest band first`() {
        val rarities = PetSpeciesCatalogue.ALL.map { it.rarity.ordinal }
        assertEquals(rarities.sorted(), rarities)
    }

    @Test
    fun `every band a hatch can land in holds at least one species`() {
        // A hatch always produces something, so a priced band with nothing in it is an egg with
        // no answer.
        HatchRates.WEIGHTS.keys.forEach { rarity ->
            assertTrue("$rarity is priced and unstocked", PetSpeciesCatalogue.inBand(rarity).isNotEmpty())
        }
    }

    @Test
    fun `a band is its species in catalogue order`() {
        assertEquals(
            listOf(PetSpeciesCatalogue.IRON_CUB, PetSpeciesCatalogue.CHALK_SPRITE),
            PetSpeciesCatalogue.inBand(Rarity.COMMON),
        )
    }

    @Test
    fun `a species without an id is not a species`() {
        assertThrows(IllegalArgumentException::class.java) { PetSpecies(id = " ", rarity = Rarity.COMMON) }
    }
}
