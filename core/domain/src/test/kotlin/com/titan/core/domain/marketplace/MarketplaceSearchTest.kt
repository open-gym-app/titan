package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MarketplaceSearchTest {

    private val chestBarbell = listing(
        id = "chest-barbell",
        rating = 4.0,
        tags = listOf("Chest", "barbell"),
        minutes = 60,
        title = "Heavy Bench Day",
        description = "Strength focus",
    )
    private val chestDumbbell = listing(
        id = "chest-dumbbell",
        rating = 3.0,
        tags = listOf("chest", "dumbbell"),
        minutes = 30,
        title = "Dumbbell Chest",
        description = "Hypertrophy and a bench",
    )
    private val legsBarbell = listing(
        id = "legs-barbell",
        rating = 5.0,
        tags = listOf("legs", "barbell"),
        minutes = 45,
        title = "Squat Day",
        description = "Lower body",
    )
    private val all = listOf(chestBarbell, chestDumbbell, legsBarbell)

    private suspend fun search(vararg filters: MarketplaceFilter) =
        SearchMarketplaceUseCase(StubMarketplaceRepository(all))(filters.toList()).map { it.id }

    @Test
    fun `no filters returns everything, best ranked first`() =
        runTest {
            assertEquals(listOf("legs-barbell", "chest-barbell", "chest-dumbbell"), search())
        }

    @Test
    fun `a muscle group filter matches the tag regardless of case`() =
        runTest {
            assertEquals(listOf("chest-barbell", "chest-dumbbell"), search(MarketplaceFilter.muscleGroup("CHEST")))
        }

    @Test
    fun `an equipment filter matches on its own`() =
        runTest {
            assertEquals(listOf("legs-barbell", "chest-barbell"), search(MarketplaceFilter.equipment("barbell")))
        }

    @Test
    fun `a duration filter is inclusive at both ends`() =
        runTest {
            assertEquals(listOf("legs-barbell"), search(MarketplaceFilter.duration(45..45)))
            assertEquals(
                listOf("legs-barbell", "chest-barbell", "chest-dumbbell"),
                search(MarketplaceFilter.duration(30..60)),
            )
            assertEquals(listOf("chest-dumbbell"), search(MarketplaceFilter.duration(30..44)))
            assertEquals(emptyList<String>(), search(MarketplaceFilter.duration(61..90)))
        }

    @Test
    fun `a text filter searches title and description, ignoring case`() =
        runTest {
            assertEquals(listOf("legs-barbell"), search(MarketplaceFilter.text("squat")))
            assertEquals(listOf("chest-barbell", "chest-dumbbell"), search(MarketplaceFilter.text("BENCH")))
        }

    @Test
    fun `a blank text query matches everything`() =
        runTest {
            assertEquals(3, search(MarketplaceFilter.text("   ")).size)
        }

    @Test
    fun `filters combine as an intersection`() =
        runTest {
            assertEquals(
                listOf("chest-barbell"),
                search(MarketplaceFilter.muscleGroup("chest"), MarketplaceFilter.equipment("barbell")),
            )
            assertEquals(
                listOf("chest-dumbbell"),
                search(
                    MarketplaceFilter.muscleGroup("chest"),
                    MarketplaceFilter.duration(0..40),
                    MarketplaceFilter.text("bench"),
                ),
            )
        }

    @Test
    fun `filters that exclude each other return nothing`() =
        runTest {
            assertEquals(
                emptyList<String>(),
                search(MarketplaceFilter.muscleGroup("legs"), MarketplaceFilter.equipment("dumbbell")),
            )
        }

    @Test
    fun `the stub serves its fixtures by default`() =
        runTest {
            val ids = SearchMarketplaceUseCase(StubMarketplaceRepository())().map { it.id }

            assertEquals(StubMarketplaceRepository.FIXTURES.map { it.id }.toSet(), ids.toSet())
            assertEquals("mp-push-pull-legs", ids.first())
        }

    @Test
    fun `a listing is validated on construction`() {
        fun build(
            downloads: Int = 0,
            rating: Double = 3.0,
            minutes: Int = 30,
        ) = MarketplaceTemplate("id", "t", "a", "d", emptyList(), downloads, rating, minutes, 0)

        assertThrows(IllegalArgumentException::class.java) { build(downloads = -1) }
        assertThrows(IllegalArgumentException::class.java) { build(rating = 5.1) }
        assertThrows(IllegalArgumentException::class.java) { build(rating = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { build(minutes = 0) }
    }
}
