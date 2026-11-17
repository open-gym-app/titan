package com.titan.core.domain.usecase

import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.GearSlot
import com.titan.core.domain.model.UnlockCondition
import com.titan.core.domain.model.WearResult
import com.titan.core.domain.repository.LoadoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WearGearUseCaseTest {

    private val pets = FakePetRepository()
    private val unlocks = FakeUnlockRepository()
    private val loadout = FakeLoadout()
    private val gear = WearGearUseCase(pets, unlocks, loadout)

    @Test
    fun `owned gear goes on in its slot`() =
        runTest {
            val pet = hatch()

            assertEquals(WearResult.Worn(replaced = null), gear.wear(pet, "headband"))
            assertEquals(mapOf("head" to "headband"), loadout.of(pet))
        }

    @Test
    fun `a second piece in the same slot replaces the first and says so`() =
        runTest {
            val pet = hatch()
            gear.wear(pet, "headband")

            assertEquals(WearResult.Worn(replaced = Gear.HEADBAND), gear.wear(pet, "crown"))
            assertEquals(mapOf("head" to "crown"), loadout.of(pet))
        }

    @Test
    fun `pieces in different slots are worn together`() =
        runTest {
            val pet = hatch()
            gear.wear(pet, "headband")
            gear.wear(pet, "shades")

            assertEquals(mapOf("head" to "headband", "face" to "shades"), loadout.of(pet))
        }

    @Test
    fun `a locked piece cannot be worn and says what it needs`() =
        runTest {
            val pet = hatch()

            assertEquals(WearResult.Locked(UnlockCondition.Level(40)), gear.wear(pet, "cape"))
            assertEquals(emptyMap<String, String>(), loadout.of(pet))

            unlocks.unlock(setOf("cape"), at = 1)
            assertEquals(WearResult.Worn(replaced = null), gear.wear(pet, "cape"))
        }

    @Test
    fun `an unknown piece or pet is answered, not thrown`() =
        runTest {
            val pet = hatch()

            assertEquals(WearResult.NotGear, gear.wear(pet, "jetpack"))
            assertEquals(WearResult.NotFound, gear.wear(pet + 1, "headband"))
        }

    @Test
    fun `tapping worn takes it off, and taking off what is not on does nothing`() =
        runTest {
            val pet = hatch()
            gear.wear(pet, "headband")

            gear.takeOff(pet, "shades")
            assertEquals(mapOf("head" to "headband"), loadout.of(pet))

            gear.takeOff(pet, "headband")
            assertEquals(emptyMap<String, String>(), loadout.of(pet))
        }

    @Test
    fun `wearing or removing gear changes no stat`() =
        runTest {
            val pet = hatch()
            val before = pets.held.single()

            gear.wear(pet, "headband")
            gear.wear(pet, "shades")
            gear.takeOff(pet, "headband")

            assertEquals(before, pets.held.single())
        }

    private suspend fun hatch(): Long =
        pets.hatch(collectedItemId = 1, speciesId = "iron_cub", hatchedAt = Instant.EPOCH).pet.id

    private class FakeLoadout : LoadoutRepository {

        private val rows = MutableStateFlow<Map<Long, Map<String, String>>>(emptyMap())

        fun of(petId: Long): Map<String, String> = rows.value[petId].orEmpty()

        override fun observeLoadout(petId: Long): Flow<Map<String, String>> = rows.map { it[petId].orEmpty() }

        override suspend fun wear(
            petId: Long,
            slot: GearSlot,
            gearKey: String,
        ) {
            rows.value += petId to (of(petId) + (slot.key to gearKey))
        }

        override suspend fun takeOff(
            petId: Long,
            slotKey: String,
        ) {
            rows.value += petId to (of(petId) - slotKey)
        }
    }
}
