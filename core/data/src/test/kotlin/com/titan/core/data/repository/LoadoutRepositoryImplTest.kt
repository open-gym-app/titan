package com.titan.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import app.cash.turbine.test
import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.entity.CollectedItemEntity
import com.titan.core.data.db.entity.PetLoadoutEntity
import com.titan.core.domain.model.GearSlot
import com.titan.core.domain.model.WearResult
import com.titan.core.domain.usecase.WearGearUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class LoadoutRepositoryImplTest : RoomTestBase() {

    private val pets by lazy { PetRepositoryImpl(db.petDao()) }
    private val loadout by lazy { LoadoutRepositoryImpl(db.loadoutDao()) }
    private val achievements by lazy {
        GamificationRepositoryImpl(
            userProgressDao = db.userProgressDao(),
            achievementDao = db.achievementDao(),
            sessionXpClaimDao = db.sessionXpClaimDao(),
            sessionLootClaimDao = db.sessionLootClaimDao(),
            sessionPetClaimDao = db.sessionPetClaimDao(),
        )
    }
    private val gear by lazy { WearGearUseCase(pets, UnlockRepositoryImpl(db.petUnlockDao()), achievements, loadout) }

    @Test
    fun `a pet holds one piece per slot, enforced by the database`() =
        runBlocking {
            val pet = hatch()

            loadout.wear(pet, GearSlot.HEAD, "headband")
            loadout.wear(pet, GearSlot.HEAD, "crown")

            assertEquals(mapOf("head" to "crown"), loadout.observeLoadout(pet).first())
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { db.backupDao().insertPetLoadouts(List(2) { PetLoadoutEntity(pet, "face", "shades") }) }
            }
            Unit
        }

    @Test
    fun `wearing and taking off go through the use case against the real store`() =
        runBlocking {
            val pet = hatch()
            assertEquals("not owned yet", WearResult.Locked, gear.wear(pet, "shades"))
            achievements.unlockAchievement("ten_sessions", Instant.EPOCH)

            assertEquals(WearResult.Worn(replaced = null), gear.wear(pet, "shades"))
            gear.takeOff(pet, "shades")

            assertEquals(emptyMap<String, String>(), loadout.observeLoadout(pet).first())
        }

    @Test
    fun `deleting a pet frees what it wore`() =
        runBlocking {
            val pet = hatch()
            loadout.wear(pet, GearSlot.HEAD, "headband")

            db.openHelper.writableDatabase.execSQL("DELETE FROM pet WHERE id = $pet")

            assertEquals(0, countRows("pet_loadout"))
        }

    @Test
    fun `an unknown slot and an unknown piece are kept and read back as written`() =
        runBlocking {
            val pet = hatch()
            db.backupDao().insertPetLoadouts(listOf(PetLoadoutEntity(pet, "tail", "a_newer_gear")))

            assertEquals(mapOf("tail" to "a_newer_gear"), loadout.observeLoadout(pet).first())
        }

    @Test
    fun `the loadout is observed as it changes`() =
        runBlocking {
            val pet = hatch()

            loadout.observeLoadout(pet).test {
                assertEquals(emptyMap<String, String>(), awaitItem())
                loadout.wear(pet, GearSlot.FACE, "shades")
                assertEquals(mapOf("face" to "shades"), awaitItem())
                loadout.takeOff(pet, "face")
                assertEquals(emptyMap<String, String>(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun hatch(): Long {
        val egg = db.collectedItemDao().insert(
            CollectedItemEntity(collectibleId = "pet_egg", source = "session_finished", acquiredAt = 0),
        )
        return pets.hatch(egg, "iron_cub", Instant.EPOCH).pet.id
    }
}
