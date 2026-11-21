package com.titan.core.domain.marketplace

import com.titan.core.domain.marketplace.MarketplaceFixtures.all
import com.titan.core.domain.marketplace.MarketplaceFixtures.classic
import com.titan.core.domain.marketplace.MarketplaceFixtures.fresh
import com.titan.core.domain.marketplace.MarketplaceFixtures.gem
import com.titan.core.domain.marketplace.MarketplaceFixtures.solid
import com.titan.core.domain.marketplace.MarketplaceFixtures.template
import com.titan.core.domain.marketplace.MarketplaceFixtures.viral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.ln

class MarketplaceRankerTest {

    @Test
    fun `the score is rating times its weight plus log downloads times its weight`() {
        val ranker = MarketplaceRanker(RankingConfig(weightRating = 2.0, weightDownloads = 3.0))

        assertEquals(4.8 * 2.0 + ln(1_001.0) * 3.0, ranker.score(classic), 1e-9)
    }

    @Test
    fun `a template with no rating and no downloads scores zero`() {
        assertEquals(0.0, MarketplaceRanker().score(fresh), 0.0)
    }

    @Test
    fun `the default weights rank the five fixtures in their known order`() {
        assertEquals(
            listOf(viral, classic, solid, gem, fresh).map { it.id },
            MarketplaceRanker().rank(all).map { it.id },
        )
    }

    @Test
    fun `rating-only weights put the best rated first`() {
        val ranker = MarketplaceRanker(RankingConfig(weightRating = 1.0, weightDownloads = 0.0))

        assertEquals(
            listOf(gem, classic, solid, viral, fresh).map { it.id },
            ranker.rank(all).map { it.id },
        )
    }

    @Test
    fun `downloads-only weights put the most downloaded first`() {
        val ranker = MarketplaceRanker(RankingConfig(weightRating = 0.0, weightDownloads = 1.0))

        assertEquals(
            listOf(viral, classic, solid, gem, fresh).map { it.id },
            ranker.rank(all).map { it.id },
        )
    }

    @Test
    fun `the order does not depend on the input order`() {
        val ranker = MarketplaceRanker()
        val expected = ranker.rank(all)

        assertEquals(expected, ranker.rank(all.reversed()))
        assertEquals(expected, ranker.rank(all.shuffled(kotlin.random.Random(42))))
    }

    @Test
    fun `equal scores go to the newest first`() {
        val older = template(id = "a", averageRating = 4.0, downloadCount = 50, createdAt = 1_000L)
        val newer = template(id = "b", averageRating = 4.0, downloadCount = 50, createdAt = 2_000L)

        assertEquals(listOf("b", "a"), MarketplaceRanker().rank(listOf(older, newer)).map { it.id })
    }

    @Test
    fun `equal scores and ages are broken by id`() {
        val second = template(id = "b", averageRating = 4.0, downloadCount = 50)
        val first = template(id = "a", averageRating = 4.0, downloadCount = 50)

        assertEquals(listOf("a", "b"), MarketplaceRanker().rank(listOf(second, first)).map { it.id })
    }

    @Test
    fun `an empty list ranks to an empty list`() {
        assertEquals(emptyList<MarketplaceTemplate>(), MarketplaceRanker().rank(emptyList()))
    }

    @Test
    fun `weights must be finite and not negative`() {
        assertThrows(IllegalArgumentException::class.java) { RankingConfig(weightRating = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { RankingConfig(weightDownloads = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { RankingConfig(weightRating = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            RankingConfig(weightDownloads = Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `a marketplace template shares no supertype with the local template`() {
        assertEquals(
            listOf(Any::class.java),
            listOf(MarketplaceTemplate::class.java.superclass) + MarketplaceTemplate::class.java.interfaces,
        )
        assertEquals(
            false,
            com.titan.core.domain.model.WorkoutTemplate::class.java
                .isAssignableFrom(MarketplaceTemplate::class.java),
        )
    }

    @Test
    fun `a template rejects out of range figures`() {
        assertThrows(IllegalArgumentException::class.java) { template(id = "x", downloadCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { template(id = "x", averageRating = 5.1) }
        assertThrows(IllegalArgumentException::class.java) { template(id = "x", averageRating = -0.1) }
        assertThrows(IllegalArgumentException::class.java) {
            template(id = "x", estimatedDuration = -kotlin.time.Duration.parse("1m"))
        }
    }
}
