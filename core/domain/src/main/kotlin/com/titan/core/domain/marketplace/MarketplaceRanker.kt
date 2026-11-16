package com.titan.core.domain.marketplace

import kotlin.math.ln

/**
 * Orders marketplace templates by
 * `score = averageRating × weightRating + ln(downloadCount + 1) × weightDownloads`.
 *
 * Deterministic: the score is a pure function of the template and [config], and [rank]
 * breaks equal scores by newest first, then by [MarketplaceTemplate.id], so the same input
 * list in any order always comes out in the same order.
 *
 * An unrated template has an [MarketplaceTemplate.averageRating] of 0 and so scores as if
 * rated zero; the model has no rating count to tell the two apart.
 */
class MarketplaceRanker(private val config: RankingConfig = RankingConfig.DEFAULT) {

    fun score(template: MarketplaceTemplate): Double =
        template.averageRating * config.weightRating +
            ln(template.downloadCount + 1.0) * config.weightDownloads

    /** [templates], highest score first; ties go to the newest, then to the lower id. */
    fun rank(templates: List<MarketplaceTemplate>): List<MarketplaceTemplate> =
        templates
            .map { it to score(it) }
            .sortedWith(
                compareByDescending<Pair<MarketplaceTemplate, Double>> { it.second }
                    .thenByDescending { it.first.createdAt }
                    .thenBy { it.first.id },
            )
            .map { it.first }
}
