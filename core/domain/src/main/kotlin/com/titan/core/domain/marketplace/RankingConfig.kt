package com.titan.core.domain.marketplace

/**
 * The weights of [MarketplaceRanker]'s score, as a value supplied at runtime so the ranking
 * can be tuned (from settings, a remote-free bundled file, a test) without touching the
 * formula.
 *
 * The defaults are a starting point, not validated against real listings: a five-star rating
 * is worth 10 points, which it takes roughly 22,000 downloads to match (`2 × 5` against
 * `ln(22027)`), so quality leads and popularity separates templates rated alike.
 *
 * @property weightRating multiplies the average rating (0–5).
 * @property weightDownloads multiplies `ln(downloadCount + 1)`.
 */
data class RankingConfig(
    val weightRating: Double = 2.0,
    val weightDownloads: Double = 1.0,
) {
    init {
        require(weightRating.isFinite() && weightRating >= 0.0) {
            "weightRating must be finite and >= 0, was $weightRating"
        }
        require(weightDownloads.isFinite() && weightDownloads >= 0.0) {
            "weightDownloads must be finite and >= 0, was $weightDownloads"
        }
    }
}
