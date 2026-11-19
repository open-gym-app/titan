package com.titan.core.data.repository

import app.cash.turbine.test
import com.titan.core.data.db.RoomTestBase
import com.titan.core.domain.gamification.PetSpeciesCatalogue
import com.titan.core.domain.model.HatchResult
import com.titan.core.domain.usecase.HatchEggUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The hatch over a real database: the unique index is the only fence, so "an egg hatches once"
 * is a claim about SQLite and has to be asked of it.
 */
class HatchEggIntegrationTest : RoomTestBase() {

    private val inventory by lazy { CollectedItemRepositoryImpl(db.collectedItemDao()) }
    private val pets by lazy { PetRepositoryImpl(db.petDao()) }

    /** Always the first common: what hatches is not what these tests are about. */
    private val hatch by lazy { HatchEggUseCase(inventory, pets, random = { 0 }, clock = { NOW }) }

    @Test
    fun `hatching an egg creates one pet, and the egg leaves the unhatched list`() =
        runBlocking {
            val egg = drop("pet_egg")
            assertEquals(listOf(egg), inventory.observeUnhatchedEggs().first().map { it.id })

            val result = hatch(egg)

            val pet = (result as HatchResult.Hatched).pet
            assertEquals(PetSpeciesCatalogue.IRON_CUB.id, pet.speciesId)
            assertEquals(Instant.ofEpochMilli(NOW), pet.hatchedAt)
            assertEquals(listOf(pet), pets.observeAll().first())
            assertTrue(inventory.observeUnhatchedEggs().first().isEmpty())
            // The drop happened: its row is still there.
            assertEquals(1, countRows("collected_item"))
        }

    @Test
    fun `hatching the same egg twice yields one pet and AlreadyHatched carrying it`() =
        runBlocking {
            val egg = drop("pet_egg")
            val first = (hatch(egg) as HatchResult.Hatched).pet

            assertEquals(HatchResult.AlreadyHatched(first), hatch(egg))
            assertEquals(1, pets.count())
        }

    @Test
    fun `two concurrent hatches of one egg yield one pet`() =
        runBlocking {
            val egg = drop("pet_egg")

            val results = withContext(Dispatchers.IO) {
                List(8) { async { hatch(egg) } }.awaitAll()
            }

            assertEquals(1, pets.count())
            val pet = pets.observeAll().first().single()
            assertEquals(1, results.count { it is HatchResult.Hatched })
            assertEquals(7, results.count { it == HatchResult.AlreadyHatched(pet) })
        }

    @Test
    fun `the store answers a lost race with the pet that won`() =
        runBlocking {
            val egg = drop("pet_egg")
            val winner = pets.hatch(egg, "atlas", Instant.ofEpochMilli(NOW))

            val loser = pets.hatch(egg, "iron_cub", Instant.ofEpochMilli(NOW + 1))

            assertTrue(winner.isNew)
            assertFalse(loser.isNew)
            assertEquals(winner.pet, loser.pet)
            assertEquals("atlas", loser.pet.speciesId)
        }

    @Test
    fun `the store refuses to hatch a row that is not there`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { pets.hatch(404, "iron_cub", Instant.ofEpochMilli(NOW)) }
        }
    }

    @Test
    fun `a trophy and a missing row are answered, not thrown`() =
        runBlocking {
            assertEquals(HatchResult.NotAnEgg, hatch(drop("iron_crown")))
            assertEquals(HatchResult.NotFound, hatch(404))
            assertEquals(0, pets.count())
        }

    @Test
    fun `the first pet is active and the second is not`() =
        runBlocking {
            val first = (hatch(drop("pet_egg")) as HatchResult.Hatched).pet
            val second = (hatch(drop("pet_egg")) as HatchResult.Hatched).pet

            assertTrue(first.isActive)
            assertFalse(second.isActive)
            assertEquals(first.id, pets.observeActive().first()?.id)
        }

    @Test
    fun `only eggs are listed, oldest drop first`() =
        runBlocking {
            drop("iron_crown", at = 1)
            val later = drop("pet_egg", at = 30)
            val earlier = drop("pet_egg", at = 20)

            assertEquals(listOf(earlier, later), inventory.observeUnhatchedEggs().first().map { it.id })
        }

    @Test
    fun `the unhatched list follows drops and hatches while it is observed`() =
        runBlocking {
            val first = drop("pet_egg", at = 10)

            inventory.observeUnhatchedEggs().test {
                assertEquals(listOf(first), awaitItem().map { it.id })

                // A session finishes behind the screen and drops another egg.
                val second = drop("pet_egg", at = 20)
                assertEquals(listOf(first, second), awaitItem().map { it.id })

                hatch(first)
                assertEquals(listOf(second), awaitItem().map { it.id })

                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun drop(
        collectibleId: String,
        at: Long = NOW - 1,
    ): Long {
        inventory.recordDrop(collectibleId, "session_finished", Instant.ofEpochMilli(at))
        return inventory.observeRecent(limit = 100).first().maxOf { it.id }
    }

    private companion object {
        const val NOW = 1_788_603_600_000L
    }
}
