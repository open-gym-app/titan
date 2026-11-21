package com.titan.core.domain.marketplace

import com.titan.core.domain.repository.MarketplaceRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Five listings chosen so the default weights, rating-only weights and downloads-only
 * weights each produce a different order, and so every filter keeps a different subset.
 *
 * Default scores (rating × 1.0 + ln(downloads + 1) × 0.5):
 * viral ≈ 9.908, classic ≈ 8.254, solid ≈ 6.308, gem ≈ 6.199, fresh = 0.
 */
object MarketplaceFixtures {

    val classic = template(
        id = "a-classic",
        title = "Classic Push",
        description = "Bench press focused push day",
        tags = listOf("chest", "barbell"),
        downloadCount = 1_000,
        averageRating = 4.8,
        estimatedDuration = 60.minutes,
    )

    val viral = template(
        id = "b-viral",
        title = "Viral Leg Burner",
        description = "No equipment needed",
        tags = listOf("legs", "bodyweight"),
        downloadCount = 1_000_000,
        averageRating = 3.0,
        estimatedDuration = 20.minutes,
    )

    val gem = template(
        id = "c-gem",
        title = "Deadlift Gem",
        description = "Heavy pulls for the posterior chain",
        tags = listOf("back", "barbell"),
        downloadCount = 10,
        averageRating = 5.0,
        estimatedDuration = 45.minutes,
    )

    val fresh = template(
        id = "d-fresh",
        title = "Fresh Upload",
        description = "Dumbbell chest and shoulders",
        tags = listOf("chest", "dumbbell"),
        downloadCount = 0,
        averageRating = 0.0,
        estimatedDuration = 30.minutes,
    )

    val solid = template(
        id = "e-solid",
        title = "Solid Full Body",
        description = "Squat and BENCH in one session",
        tags = listOf("Chest", "barbell", "legs"),
        downloadCount = 100,
        averageRating = 4.0,
        estimatedDuration = 75.minutes,
    )

    /** In neither id nor rank order, so a test cannot pass by preserving input order. */
    val all = listOf(fresh, classic, solid, viral, gem)

    fun template(
        id: String,
        title: String = "Template $id",
        description: String = "",
        tags: List<String> = emptyList(),
        downloadCount: Long = 0,
        averageRating: Double = 0.0,
        estimatedDuration: Duration = 30.minutes,
        createdAt: Long = 1_700_000_000_000L,
    ) = MarketplaceTemplate(
        id = id,
        title = title,
        author = "author-$id",
        description = description,
        tags = tags,
        downloadCount = downloadCount,
        averageRating = averageRating,
        estimatedDuration = estimatedDuration,
        createdAt = createdAt,
    )
}

/** Returns a hardcoded catalogue — [MarketplaceFixtures.all] unless told otherwise. */
class StubMarketplaceRepository(private val templates: List<MarketplaceTemplate> = MarketplaceFixtures.all) :
    MarketplaceRepository {

    override suspend fun getTemplates(): List<MarketplaceTemplate> = templates
}
