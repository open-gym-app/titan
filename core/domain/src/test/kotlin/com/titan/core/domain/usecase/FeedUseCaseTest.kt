package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.model.FeedResult
import com.titan.core.domain.model.PantryEntry
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.model.TitanVitals
import com.titan.core.domain.repository.PantryRepository
import com.titan.core.domain.repository.TitanStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedUseCaseTest {

    private val titan = MutableStateFlow(TitanRules.starting(NOW).copy(vitals = TitanVitals(22, 80, 55)))
    private val pantry = FakePantry(mutableMapOf("steak" to mutableListOf(11L, 12L)))
    private val feed = FeedUseCase(pantry, OneTitan(titan))

    @Test
    fun `feeding eats the oldest copy and applies the previewed effect`() =
        runTest {
            val preview = TitanRules.previewFeed(titan.value, Collectibles.STEAK)

            val result = feed(PET, "steak")

            assertEquals(FeedResult.Fed(preview), result)
            assertEquals(listOf(11L), pantry.eaten)
            assertEquals(preview.after, titan.value.vitals)
            assertEquals(TitanActivity.EAT, titan.value.activity)
        }

    @Test
    fun `a full titan is not fed, and nothing is eaten`() =
        runTest {
            titan.value = titan.value.copy(vitals = TitanVitals(100, 80, 55))
            val before = titan.value

            assertEquals(FeedResult.Full, feed(PET, "steak"))
            assertTrue(pantry.eaten.isEmpty())
            assertEquals(before, titan.value)
        }

    @Test
    fun `none in stock is out of stock`() =
        runTest {
            assertEquals(FeedResult.OutOfStock, feed(PET, "rice"))
        }

    @Test
    fun `a trophy or an unknown id is not food`() =
        runTest {
            assertEquals(FeedResult.NotFood, feed(PET, "iron_crown"))
            assertEquals(FeedResult.NotFood, feed(PET, "ambrosia"))
        }

    @Test
    fun `a pet that is not there is not found, and nothing is eaten`() =
        runTest {
            assertEquals(FeedResult.NotFound, feed(PET + 1, "steak"))
            assertTrue(pantry.eaten.isEmpty())
        }

    private class FakePantry(private val stock: MutableMap<String, MutableList<Long>>) : PantryRepository {

        val eaten = mutableListOf<Long>()

        override fun observePantry(): Flow<List<PantryEntry>> = flowOf(emptyList())

        override suspend fun oldestInStock(foodId: String): Long? = stock[foodId]?.firstOrNull { it !in eaten }

        override suspend fun consume(
            collectedItemId: Long,
            petId: Long,
            fedAt: Long,
        ) {
            check(collectedItemId !in eaten)
            eaten += collectedItemId
        }

        override suspend fun markSeen(foodId: String) = Unit

        override suspend fun <T> atomically(block: suspend () -> T): T = block()
    }

    private class OneTitan(private val state: MutableStateFlow<TitanState>) : TitanStateRepository {

        override fun observe(petId: Long): Flow<TitanState?> = state

        override suspend fun <R> modify(
            petId: Long,
            change: (state: TitanState, now: Long) -> TitanChange<R>,
        ): R? {
            if (petId != PET) return null
            val decided = change(state.value, NOW)
            decided.state?.let { state.value = it }
            return decided.result
        }
    }

    private companion object {
        const val PET = 3L
        const val NOW = 1_788_603_600_000L
    }
}
