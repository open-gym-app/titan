package com.titan.core.domain.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.ln

class MarketplaceRankerTest {

    private val ranker = MarketplaceRanker()

    @Test
    fun `the score is rating times its weight plus ln of downloads plus one times its weight`() {
        val config = RankingConfig(weightRating = 3.0, weightDownloads = 0.5)
        val score = MarketplaceRanker(config).score(listing("a", rating = 4.0, downloads = 99))

        assertEquals(4.0 * 3.0 + ln(100.0) * 0.5, score, 1e-9)
    }

    @Test
    fun `a template nobody has downloaded scores on rating alone`() {
        assertEquals(8.0, ranker.score(listing("a", rating = 4.0, downloads = 0)), 1e-9)
    }

    @Test
    fun `five known fixtures rank in the expected order with the default weights`() {
        // Scores at 2.0 / 1.0: top 9.4+9.21=18.61, solid 8.8+6.91=15.71, niche 9.6+2.40=12.00,
        // popular 6.0+11.51=17.51, new 0+0=0 — hand-computed, so a change to the formula fails here.
        val fixtures = listOf(
            listing("new", rating = 0.0, downloads = 0),
            listing("niche", rating = 4.8, downloads = 10),
            listing("solid", rating = 4.4, downloads = 1_000),
            listing("popular", rating = 3.0, downloads = 100_000),
            listing("top", rating = 4.7, downloads = 10_000),
        )

        assertEquals(
            listOf("top", "popular", "solid", "niche", "new"),
            ranker.rank(fixtures).map { it.id },
        )
    }

    @Test
    fun `the same listings rank identically whatever order they arrive in`() {
        val fixtures = listOf(
            listing("a", rating = 4.0, downloads = 10),
            listing("b", rating = 4.5, downloads = 5),
            listing("c", rating = 3.0, downloads = 5_000),
            listing("d", rating = 4.0, downloads = 10),
        )

        val expected = ranker.rank(fixtures).map { it.id }

        assertEquals(expected, ranker.rank(fixtures.reversed()).map { it.id })
        assertEquals(expected, ranker.rank(fixtures.shuffled(java.util.Random(7))).map { it.id })
    }

    @Test
    fun `equal scores go to the newer listing, then to the lower id`() {
        val fixtures = listOf(
            listing("b", createdAt = 2_000),
            listing("a", createdAt = 2_000),
            listing("old", createdAt = 1_000),
            listing("newest", createdAt = 3_000),
        )

        assertEquals(listOf("newest", "a", "b", "old"), ranker.rank(fixtures).map { it.id })
    }

    @Test
    fun `changing the weights reorders without changing the formula`() {
        val loved = listing("loved", rating = 5.0, downloads = 10)
        val popular = listing("popular", rating = 3.0, downloads = 100_000)

        val byRating = MarketplaceRanker(RankingConfig(weightRating = 10.0, weightDownloads = 0.0))
        val byDownloads = MarketplaceRanker(RankingConfig(weightRating = 0.0, weightDownloads = 1.0))

        assertEquals(listOf("loved", "popular"), byRating.rank(listOf(popular, loved)).map { it.id })
        assertEquals(listOf("popular", "loved"), byDownloads.rank(listOf(loved, popular)).map { it.id })
    }

    @Test
    fun `an empty list ranks to an empty list`() {
        assertEquals(emptyList<String>(), ranker.rank(emptyList()).map { it.id })
    }

    @Test
    fun `weights must be finite and not negative`() {
        assertThrows(IllegalArgumentException::class.java) { RankingConfig(weightRating = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { RankingConfig(weightDownloads = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { RankingConfig(weightRating = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { RankingConfig(weightDownloads = Double.POSITIVE_INFINITY) }
    }
}
