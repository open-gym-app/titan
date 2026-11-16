package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.PetSpeciesCatalogue
import com.titan.core.domain.model.HatchResult
import com.titan.core.domain.model.PetSpecies
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.Rarity
import com.titan.core.domain.random.RandomSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HatchEggUseCaseTest {

    private val inventory = FakeCollectedItemRepository()
    private val pets = FakePetRepository()

    // ─── The four answers ───────────────────────────────────────────────────────────────

    @Test
    fun `an unhatched egg hatches into exactly one pet, starting from zero`() =
        runTest {
            val egg = drop("pet_egg")

            val result = hatcher(0, 0)(egg)

            val pet = (result as HatchResult.Hatched).pet
            assertEquals(listOf(pet), pets.held)
            assertEquals(0L, pet.xp)
            assertEquals(PetStats.ZERO, pet.stats)
            assertNull(pet.nickname)
            assertEquals(Instant.ofEpochMilli(NOW), pet.hatchedAt)
        }

    @Test
    fun `hatching the same egg again returns the pet it already hatched, and draws nothing`() =
        runTest {
            val egg = drop("pet_egg")
            val first = (hatcher(0, 0)(egg) as HatchResult.Hatched).pet

            // No numbers queued: a second roll would fail the test by asking for one.
            val again = hatcher()(egg)

            assertEquals(HatchResult.AlreadyHatched(first), again)
            assertEquals(1, pets.held.size)
        }

    @Test
    fun `a call that loses the race is answered with the winner's pet`() =
        runTest {
            val egg = drop("pet_egg")
            // The other call lands after this one has checked and before it writes.
            pets.beforeNextHatch = { pets.hatch(egg, "atlas", Instant.ofEpochMilli(NOW)) }

            val result = hatcher(0, 0)(egg)

            val winner = pets.held.single()
            assertEquals("atlas", winner.speciesId)
            assertEquals(HatchResult.AlreadyHatched(winner), result)
        }

    @Test
    fun `a drop that is not an egg is not hatched`() =
        runTest {
            val trophy = drop("iron_crown")

            assertEquals(HatchResult.NotAnEgg, hatcher()(trophy))
            assertTrue(pets.held.isEmpty())
        }

    @Test
    fun `a row that is not there is not found`() =
        runTest {
            assertEquals(HatchResult.NotFound, hatcher()(404))
            assertTrue(pets.held.isEmpty())
        }

    // ─── Which pet is active ────────────────────────────────────────────────────────────

    @Test
    fun `the first pet is active and the second is not`() =
        runTest {
            val first = (hatcher(0, 0)(drop("pet_egg")) as HatchResult.Hatched).pet
            val second = (hatcher(0, 0)(drop("pet_egg")) as HatchResult.Hatched).pet

            assertTrue(first.isActive)
            assertFalse(second.isActive)
        }

    // ─── The roll ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the species is exact for a fixed sequence, at each rarity boundary`() =
        runTest {
            // On the shipped rates: 0..49 common, 50..77 uncommon, 78..92 rare, 93..98 epic, 99.
            val cases = listOf(
                listOf(0, 0) to PetSpeciesCatalogue.IRON_CUB,
                listOf(49, 1) to PetSpeciesCatalogue.CHALK_SPRITE,
                listOf(50, 0) to PetSpeciesCatalogue.PLATE_TORTOISE,
                listOf(77, 0) to PetSpeciesCatalogue.PLATE_TORTOISE,
                listOf(78, 0) to PetSpeciesCatalogue.KETTLE_RAM,
                listOf(92, 0) to PetSpeciesCatalogue.KETTLE_RAM,
                listOf(93, 0) to PetSpeciesCatalogue.BARBELL_DRAKE,
                listOf(98, 0) to PetSpeciesCatalogue.BARBELL_DRAKE,
                listOf(99, 0) to PetSpeciesCatalogue.ATLAS,
            )

            cases.forEach { (numbers, expected) ->
                val result = hatcher(*numbers.toIntArray())(drop("pet_egg"))

                assertEquals("for $numbers", expected.id, (result as HatchResult.Hatched).pet.speciesId)
            }
        }

    @Test
    fun `a band of one species is that species, and the second draw is over one`() =
        runTest {
            val bounds = mutableListOf<Int>()
            val random = RandomSource { bound ->
                bounds += bound
                if (bounds.size == 1) 99 else 0
            }

            val result = HatchEggUseCase(inventory, pets, random, { NOW })(drop("pet_egg"))

            assertEquals(PetSpeciesCatalogue.ATLAS.id, (result as HatchResult.Hatched).pet.speciesId)
            assertEquals(listOf(100, 1), bounds)
        }

    @Test
    fun `a catalogue with an empty band is a broken catalogue, not a silent nothing`() =
        runTest {
            val commonsOnly = listOf(PetSpecies("only_common", Rarity.COMMON))
            val egg = drop("pet_egg")
            val hatch = HatchEggUseCase(inventory, pets, sequence(99, 0), { NOW }, commonsOnly)

            assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { hatch(egg) } }
            assertTrue(pets.held.isEmpty())
        }

    private fun hatcher(vararg numbers: Int) = HatchEggUseCase(inventory, pets, sequence(*numbers), { NOW })

    /** Hands out [numbers] in order and fails on a draw nobody queued. */
    private fun sequence(vararg numbers: Int): RandomSource {
        val queue = ArrayDeque(numbers.toList())
        return RandomSource { queue.removeFirst() }
    }

    private suspend fun drop(collectibleId: String): Long {
        inventory.recordDrop(collectibleId, "session_finished", Instant.ofEpochMilli(NOW - 1))
        return inventory.recorded.last().id
    }

    private companion object {
        const val NOW = 1_788_603_600_000L
    }
}
