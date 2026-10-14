package com.titan.core.domain.marketplace

import com.titan.core.domain.model.MarketplaceTemplate
import kotlin.math.ln

/**
 * Orders marketplace listings by
 * `score = averageRating × weightRating + ln(downloadCount + 1) × weightDownloads`.
 *
 * The logarithm is why a viral template does not bury every well-rated one: the ten-thousandth
 * download is worth far less than the tenth.
 *
 * Deterministic: equal scores are broken by newer [MarketplaceTemplate.createdAt] first, then
 * by [MarketplaceTemplate.id], so the same listings always come out in the same order.
 */
class MarketplaceRanker(private val config: RankingConfig = RankingConfig()) {

    fun score(template: MarketplaceTemplate): Double =
        template.averageRating * config.weightRating +
            ln(template.downloadCount + 1.0) * config.weightDownloads

    /** [templates], best first. */
    fun rank(templates: List<MarketplaceTemplate>): List<MarketplaceTemplate> =
        templates.sortedWith(
            compareByDescending<MarketplaceTemplate> { score(it) }
                .thenByDescending { it.createdAt }
                .thenBy { it.id },
        )
}
