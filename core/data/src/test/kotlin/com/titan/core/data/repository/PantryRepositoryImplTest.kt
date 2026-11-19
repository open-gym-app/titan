package com.titan.core.data.repository

import app.cash.turbine.test
import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.entity.WorkoutSessionEntity
import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.model.FeedResult
import com.titan.core.domain.model.PantrySource
import com.titan.core.domain.model.TitanVitals
import com.titan.core.domain.usecase.FeedUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PantryRepositoryImplTest : RoomTestBase() {

    private var now = HATCHED_AT
    private val inventory by lazy { CollectedItemRepositoryImpl(db.collectedItemDao()) }
    private val pantry by lazy { PantryRepositoryImpl(db) }
    private val titans by lazy { TitanStateRepositoryImpl(db) { now } }
    private val feed by lazy { FeedUseCase(pantry, titans) }

    @Test
    fun `the pantry lists every food, empty ones included, with its count`() =
        runBlocking {
            drop("steak")
            drop("eggs")
            drop("eggs")

            val entries = pantry.observePantry().first()

            assertEquals(Collectibles.FOOD, entries.map { it.food })
            assertEquals(1, entries.single { it.food == Collectibles.STEAK }.count)
            assertEquals(2, entries.single { it.food == Collectibles.EGGS }.count)
            assertEquals(0, entries.single { it.food == Collectibles.RICE }.count)
        }

    @Test
    fun `a food says which session its newest drop came from`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(
                WorkoutSessionEntity(
                    name = "Push Day",
                    completedAt = 5_000,
                    isInProgress = false,
                    lastActiveAt = 5_000,
                    createdAt = 1,
                    updatedAt = 5_000,
                ),
            )
            drop("steak", at = 1)
            drop("steak", at = 2, sessionId = sessionId)

            val steak = pantry.observePantry().first().single { it.food == Collectibles.STEAK }

            assertEquals(PantrySource(sessionId, "Push Day", 5_000), steak.newestFrom)
            assertNull(pantry.observePantry().first().single { it.food == Collectibles.RICE }.newestFrom)
        }

    @Test
    fun `new clears once seen and returns only with a later drop`() =
        runBlocking {
            drop("steak")
            assertTrue(steak().isNew)

            pantry.markSeen("steak")
            assertFalse(steak().isNew)

            drop("steak")
            assertTrue(steak().isNew)
        }

    @Test
    fun `feeding lowers the count by exactly one and applies the previewed effect`() =
        runBlocking {
            val petId = hatch()
            drop("steak")
            drop("steak")

            val result = feed(petId, "steak")

            assertTrue(result is FeedResult.Fed)
            assertEquals(1, steak().count)
            assertEquals(
                (result as FeedResult.Fed).effect.after,
                titans.observe(petId).first()!!.vitals,
            )
        }

    @Test
    fun `the oldest copy is the one eaten, so a newer one stays new`() =
        runBlocking {
            val petId = hatch()
            val old = drop("steak", at = 1)
            pantry.markSeen("steak")
            drop("steak", at = 2)

            feed(petId, "steak")

            assertEquals(
                old,
                db.query("SELECT collected_item_id FROM pet_feed", emptyArray()).use {
                    it.moveToFirst()
                    it.getLong(0)
                },
            )
            assertTrue(steak().isNew)
        }

    @Test
    fun `two concurrent feeds of the last item give one fed and one out of stock`() =
        runBlocking {
            val petId = hatch()
            drop("oats")

            val results = withContext(Dispatchers.IO) { List(2) { async { feed(petId, "oats") } }.awaitAll() }

            assertEquals(1, results.count { it is FeedResult.Fed })
            assertEquals(1, results.count { it == FeedResult.OutOfStock })
            assertEquals(0, pantry.observePantry().first().single { it.food == Collectibles.OATS }.count)
        }

    @Test
    fun `fuel never passes the top, and a full titan eats nothing`() =
        runBlocking {
            val petId = hatch()
            repeat(3) { drop("steak") }

            // A titan starts at 80 fuel: one steak (+40) tops it out rather than going past.
            assertTrue(feed(petId, "steak") is FeedResult.Fed)
            assertEquals(TitanVitals.MAX, titans.observe(petId).first()!!.vitals.fuel)

            assertEquals(FeedResult.Full, feed(petId, "steak"))
            assertEquals("a full titan ate nothing", 2, steak().count)
        }

    @Test
    fun `an item already eaten is refused by the store`() =
        runBlocking {
            val petId = hatch()
            val item = drop("steak")
            pantry.consume(item, petId, fedAt = 1)

            assertThrows(IllegalStateException::class.java) { runBlocking { pantry.consume(item, petId, fedAt = 2) } }
            Unit
        }

    @Test
    fun `the pantry is observed as it changes`() =
        runBlocking {
            val petId = hatch()
            drop("steak")

            pantry.observePantry().test {
                assertEquals(1, awaitItem().single { it.food == Collectibles.STEAK }.count)
                feed(petId, "steak")
                // The feed writes the titan and the pantry in one transaction: one emission.
                assertEquals(0, awaitItem().single { it.food == Collectibles.STEAK }.count)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun steak() = pantry.observePantry().first().single { it.food == Collectibles.STEAK }

    private suspend fun hatch(): Long {
        val egg = drop("pet_egg")
        return PetRepositoryImpl(db.petDao()).hatch(egg, "iron_cub", Instant.ofEpochMilli(HATCHED_AT)).pet.id
    }

    private suspend fun drop(
        id: String,
        at: Long = 1,
        sessionId: Long? = null,
    ): Long {
        inventory.recordDrop(id, "session_finished", Instant.ofEpochMilli(at), sessionId)
        return db.query("SELECT MAX(id) FROM collected_item", emptyArray()).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    private companion object {
        const val HATCHED_AT = 1_788_603_600_000L
    }
}
