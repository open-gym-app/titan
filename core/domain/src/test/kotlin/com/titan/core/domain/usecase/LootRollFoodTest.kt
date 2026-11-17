package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.gamification.DropTableEvaluator
import com.titan.core.domain.gamification.FoodDrop
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.random.RandomSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The food half of the roll. The trophy half's own suite, `LootRollUseCaseTest`, is untouched. */
class LootRollFoodTest {

    private val gamification = FakeGamificationRepository()
    private val inventory = FakeCollectedItemRepository()
    private val pets = FakePetRepository()
    private val sessions = FakeSessionRepository()

    @Test
    fun `with no titan the roll draws exactly what it always did`() =
        runTest {
            val trophyNumbers = mutableListOf<Int>()
            val roll = rolling(
                trophy = { bound ->
                    trophyNumbers += bound
                    0
                },
                food = { error("no food without a titan") },
            )

            val result = roll.roll(session(paidSets = 10), FINISHED_AT)

            assertEquals(Collectibles.CHALK_BLOCK, result.drop)
            assertTrue(result.food.isEmpty())
            assertEquals("one band draw and one entry draw, as ever", 2, trophyNumbers.size)
            assertEquals(1, inventory.recorded.size)
        }

    @Test
    fun `with a titan a fixed sequence yields the exact trophy and the exact food, independently`() =
        runTest {
            hatch()
            val foodNumbers = ArrayDeque(listOf(0, 7))
            val roll = rolling(trophy = { 0 }, food = { foodNumbers.removeFirst() })

            val result = roll.roll(session(paidSets = FoodDrop.SECOND_ITEM_AT), FINISHED_AT)

            assertEquals(Collectibles.CHALK_BLOCK, result.drop)
            assertEquals(listOf(Collectibles.STEAK, Collectibles.BERRIES), result.food)
            assertEquals(listOf("chalk_block", "steak", "berries"), inventory.recorded.map { it.collectibleId })
            assertTrue(inventory.recorded.all { it.acquiredAt == FINISHED_AT })
        }

    @Test
    fun `a session that drops no trophy still pays its food`() =
        runTest {
            hatch()
            val roll = rolling(trophy = { bound -> bound - 1 }, food = { 2 })

            val result = roll.roll(session(paidSets = 3), FINISHED_AT)

            assertNull(result.drop)
            assertEquals(listOf(Collectibles.SHAKE), result.food)
        }

    @Test
    fun `re-running the cascade drops nothing at all`() =
        runTest {
            hatch()
            val roll = rolling(trophy = { 0 }, food = { 0 })
            val sessionId = session(paidSets = 3)
            roll.roll(sessionId, FINISHED_AT)

            val again = roll.roll(sessionId, FINISHED_AT)

            assertNull(again.drop)
            assertTrue(again.food.isEmpty())
            assertEquals(2, inventory.recorded.size)
        }

    private suspend fun hatch() {
        pets.hatch(collectedItemId = 99, speciesId = "iron_cub", hatchedAt = Instant.EPOCH)
    }

    private fun rolling(
        trophy: RandomSource,
        food: RandomSource,
    ) = LootRollUseCase(
        dropTable = DropTableEvaluator(trophy),
        collectedItemRepository = inventory,
        gamificationRepository = gamification,
        petRepository = pets,
        sessionRepository = sessions,
        foodDrop = FoodDrop(food),
    )

    private suspend fun session(paidSets: Int): Long {
        val id = sessions.insert(
            WorkoutSession(completedAt = 1, isInProgress = false, lastActiveAt = 1, createdAt = 0, updatedAt = 1),
        )
        val slot = sessions.addExercise(sessionId = id)
        repeat(paidSets) { sessions.addSet(slot, reps = 5) }
        return id
    }

    private companion object {
        val FINISHED_AT: Instant = Instant.parse("2026-11-12T18:00:00Z")
    }
}
