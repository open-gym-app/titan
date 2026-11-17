package com.titan.core.data.repository

import app.cash.turbine.test
import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.entity.CollectedItemEntity
import com.titan.core.data.db.entity.PetEntity
import com.titan.core.data.db.entity.PetStateEntity
import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.model.ChooseActivityResult
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.TitanVitals
import com.titan.core.domain.usecase.ChooseActivityUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TitanStateRepositoryImplTest : RoomTestBase() {

    private var now = HATCHED_AT
    private val pets by lazy { PetRepositoryImpl(db.petDao()) }
    private val repository by lazy { TitanStateRepositoryImpl(db) { now } }

    @Test
    fun `a hatch writes the starting state in its own transaction`() =
        runBlocking {
            val petId = hatch()

            val row = db.petStateDao().get(petId)!!

            assertEquals(TitanVitals.START.fuel, row.fuel)
            assertEquals(TitanActivity.IDLE.key, row.activity)
            assertEquals(HATCHED_AT, row.updatedAt)
            assertEquals(TitanRules.starting(HATCHED_AT), repository.observe(petId).first())
        }

    @Test
    fun `a read advances the stored state to now and never writes`() =
        runBlocking {
            val petId = hatch()
            now = HATCHED_AT + DAY

            val read = repository.observe(petId).first()!!

            assertEquals(TitanRules.advance(TitanRules.starting(HATCHED_AT), HATCHED_AT + DAY, seed = petId), read)
            assertEquals(HATCHED_AT, db.petStateDao().get(petId)!!.updatedAt)
        }

    @Test
    fun `a write stores the state it was advanced to, and survives a new repository`() =
        runBlocking {
            val petId = hatch()
            now = HATCHED_AT + HOUR

            val result = ChooseActivityUseCase(repository)(petId, TitanActivity.REST)

            val chosen = (result as ChooseActivityResult.Chosen).state
            assertEquals(now, chosen.updatedAt)
            // A process death: a repository built afresh over the same database reads the same titan.
            assertEquals(chosen, TitanStateRepositoryImpl(db) { now }.observe(petId).first())
        }

    @Test
    fun `a write that decides nothing stores nothing`() =
        runBlocking {
            val petId = hatch()
            now = HATCHED_AT + HOUR

            assertEquals("refused", repository.modify(petId) { _, _ -> TitanChange(state = null, result = "refused") })
            assertEquals(HATCHED_AT, db.petStateDao().get(petId)!!.updatedAt)
        }

    @Test
    fun `a pet that is not there has no state and no write`() =
        runBlocking {
            assertNull(repository.observe(404).first())
            assertNull(repository.modify(404) { state, _ -> TitanChange(state, Unit) })
        }

    @Test
    fun `a pet with no state row reads the starting state from when it hatched`() =
        runBlocking {
            val egg = db.collectedItemDao().insert(egg())
            // Hatched by a build before this one: the pet row and nothing else.
            val petId = db.petDao().insert(
                PetEntity(speciesId = "iron_cub", hatchedFromItemId = egg, hatchedAt = HATCHED_AT),
            )
            now = HATCHED_AT + HOUR

            assertEquals(
                TitanRules.advance(TitanRules.starting(HATCHED_AT), now, seed = petId),
                repository.observe(petId).first(),
            )
        }

    @Test
    fun `two concurrent writes do not lose an update`() =
        runBlocking {
            val petId = hatch()

            withContext(Dispatchers.IO) {
                List(20) {
                    async {
                        repository.modify(petId) { state, _ ->
                            TitanChange(state.copy(vitals = state.vitals.plus(TitanVital.SPIRIT, -1)), Unit)
                        }
                    }
                }.awaitAll()
            }

            assertEquals(TitanVitals.START.spirit - 20, repository.observe(petId).first()!!.vitals.spirit)
        }

    @Test
    fun `hostile stored values read back without a throw`() =
        runBlocking {
            val petId = hatch()
            db.petStateDao().upsert(
                PetStateEntity(
                    petId = petId,
                    fuel = -40,
                    energy = 900,
                    spirit = Int.MIN_VALUE,
                    activity = "fly",
                    activityStartedAt = HATCHED_AT + 100 * DAY,
                    activityEndsAt = Long.MAX_VALUE,
                    resumeActivity = "teleport",
                    updatedAt = HATCHED_AT + 100 * DAY,
                ),
            )
            now = HATCHED_AT + HOUR

            val read = repository.observe(petId).first()!!

            assertEquals(now, read.updatedAt)
            assertTrue(read.vitals.fuel in 0..TitanVitals.MAX)
            assertTrue(read.vitals.energy in 0..TitanVitals.MAX)
            assertTrue(read.vitals.spirit in 0..TitanVitals.MAX)
            assertTrue("an unknown activity reads as idle and moves on from there", read.startedAt <= now)
            assertNull(read.resumeActivity)
        }

    @Test
    fun `the state is observed as it is written`() =
        runBlocking {
            val petId = hatch()

            repository.observe(petId).test {
                assertEquals(TitanActivity.IDLE, awaitItem()?.activity)
                ChooseActivityUseCase(repository)(petId, TitanActivity.WALK)
                assertEquals(TitanActivity.WALK, awaitItem()?.activity)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleting a pet deletes its state`() =
        runBlocking {
            val petId = hatch()

            db.openHelper.writableDatabase.execSQL("DELETE FROM pet WHERE id = $petId")

            assertEquals(0, countRows("pet_state"))
        }

    private suspend fun hatch(): Long {
        val egg = db.collectedItemDao().insert(egg())
        return pets.hatch(egg, "iron_cub", Instant.ofEpochMilli(HATCHED_AT)).pet.id
    }

    private fun egg() = CollectedItemEntity(collectibleId = "pet_egg", source = "session_finished", acquiredAt = 0)

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
        const val HATCHED_AT = 1_788_603_600_000L
    }
}
