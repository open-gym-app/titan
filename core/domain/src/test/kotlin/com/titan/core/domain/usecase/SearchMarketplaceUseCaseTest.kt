package com.titan.core.domain.usecase

import com.titan.core.domain.marketplace.MarketplaceFilter
import com.titan.core.domain.marketplace.MarketplaceFilter.DurationRange
import com.titan.core.domain.marketplace.MarketplaceFilter.Equipment
import com.titan.core.domain.marketplace.MarketplaceFilter.MuscleGroup
import com.titan.core.domain.marketplace.MarketplaceFilter.Text
import com.titan.core.domain.marketplace.MarketplaceFixtures
import com.titan.core.domain.marketplace.MarketplaceRanker
import com.titan.core.domain.marketplace.RankingConfig
import com.titan.core.domain.marketplace.StubMarketplaceRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class SearchMarketplaceUseCaseTest {

    private val search = SearchMarketplaceUseCase(StubMarketplaceRepository())

    private suspend fun ids(vararg filters: MarketplaceFilter) = search(filters.toList()).map { it.id }.toSet()

    @Test
    fun `no filters returns the whole catalogue ranked`() =
        runTest {
            assertEquals(
                listOf("b-viral", "a-classic", "e-solid", "c-gem", "d-fresh"),
                search().map { it.id },
            )
        }

    @Test
    fun `the muscle group filter matches a tag ignoring case`() =
        runTest {
            assertEquals(setOf("a-classic", "d-fresh", "e-solid"), ids(MuscleGroup("CHEST")))
        }

    @Test
    fun `the equipment filter matches a tag`() =
        runTest {
            assertEquals(setOf("a-classic", "c-gem", "e-solid"), ids(Equipment("barbell")))
        }

    @Test
    fun `the duration filter includes both ends of its range`() =
        runTest {
            assertEquals(setOf("c-gem", "a-classic"), ids(DurationRange(45.minutes..60.minutes)))
        }

    @Test
    fun `the text filter searches title and description ignoring case`() =
        runTest {
            assertEquals(setOf("a-classic", "e-solid"), ids(Text("bench")))
            assertEquals(setOf("c-gem"), ids(Text("  deadlift ")))
        }

    @Test
    fun `a blank text query keeps everything`() =
        runTest {
            assertEquals(MarketplaceFixtures.all.map { it.id }.toSet(), ids(Text("   ")))
        }

    @Test
    fun `a blank tag keeps everything, like a blank text query`() =
        runTest {
            val everything = MarketplaceFixtures.all.map { it.id }.toSet()

            assertEquals(everything, ids(MuscleGroup("")))
            assertEquals(everything, ids(Equipment("  ")))
        }

    @Test
    fun `filters that match nothing in common return nothing`() =
        runTest {
            assertEquals(emptySet<String>(), ids(MuscleGroup("back"), Equipment("dumbbell")))
        }

    @Test
    fun `stacked filters narrow step by step and keep the rank order`() =
        runTest {
            assertEquals(
                listOf("a-classic", "e-solid"),
                search(listOf(MuscleGroup("chest"), Equipment("barbell"))).map { it.id },
            )
            assertEquals(
                listOf("a-classic"),
                search(listOf(MuscleGroup("chest"), Equipment("barbell"), DurationRange(50.minutes..70.minutes)))
                    .map { it.id },
            )
        }

    @Test
    fun `any combination of filters returns the intersection of each alone`() =
        runTest {
            val filters = listOf(
                MuscleGroup("chest"),
                MuscleGroup("legs"),
                Equipment("barbell"),
                DurationRange(30.minutes..60.minutes),
                Text("bench"),
                Text("no equipment"),
            )
            val alone = filters.associateWith { ids(it) }

            for (mask in 1 until (1 shl filters.size)) {
                val chosen = filters.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                val expected = chosen.map { alone.getValue(it) }.reduce { acc, set -> acc intersect set }

                assertEquals("filters $chosen", expected, ids(*chosen.toTypedArray()))
            }
        }

    @Test
    fun `the ranking config passed in decides the order`() =
        runTest {
            val ratingOnly = SearchMarketplaceUseCase(
                StubMarketplaceRepository(),
                MarketplaceRanker(RankingConfig(weightRating = 1.0, weightDownloads = 0.0)),
            )

            assertEquals(
                listOf("c-gem", "a-classic", "e-solid"),
                ratingOnly(listOf(Equipment("barbell"))).map { it.id },
            )
        }
}
