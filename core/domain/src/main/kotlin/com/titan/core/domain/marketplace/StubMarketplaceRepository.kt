package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate
import com.titan.core.domain.repository.MarketplaceRepository

/** A [MarketplaceRepository] serving [FIXTURES], or whatever [listings] it is given. */
class StubMarketplaceRepository(private val listings: List<MarketplaceTemplate> = FIXTURES) :
    MarketplaceRepository {

    override suspend fun getAll(): List<MarketplaceTemplate> = listings

    companion object {
        val FIXTURES: List<MarketplaceTemplate> = listOf(
            MarketplaceTemplate(
                id = "mp-push-pull-legs",
                title = "Push Pull Legs",
                author = "Alex",
                description = "Classic three-day split for steady strength gains.",
                tags = listOf("chest", "back", "legs", "barbell"),
                downloadCount = 4_200,
                averageRating = 4.6,
                durationMinutes = 60,
                createdAt = 1_700_000_000_000,
            ),
            MarketplaceTemplate(
                id = "mp-upper-body-dumbbell",
                title = "Upper Body Dumbbell Day",
                author = "Sam",
                description = "Home-friendly upper body session with a pair of dumbbells.",
                tags = listOf("chest", "shoulders", "arms", "dumbbell"),
                downloadCount = 900,
                averageRating = 4.4,
                durationMinutes = 45,
                createdAt = 1_710_000_000_000,
            ),
            MarketplaceTemplate(
                id = "mp-quick-core",
                title = "Ten Minute Core",
                author = "Jo",
                description = "Short bodyweight core finisher.",
                tags = listOf("core", "bodyweight"),
                downloadCount = 150,
                averageRating = 3.9,
                durationMinutes = 10,
                createdAt = 1_720_000_000_000,
            ),
        )
    }
}
