package com.titan.core.domain.marketplace

/**
 * The weights [MarketplaceRanker] scores with.
 *
 * A runtime value rather than constants in the ranker, so the balance between "well
 * rated" and "widely used" can be tuned from wherever the config is read — a remote
 * flag, a settings screen, a test — without a code change.
 *
 * @property weightRating multiplier on [MarketplaceTemplate.averageRating].
 * @property weightDownloads multiplier on `ln(downloadCount + 1)`. The log keeps a
 *   template with a million downloads from burying everything else on volume alone.
 */
data class RankingConfig(
    val weightRating: Double = DEFAULT_WEIGHT_RATING,
    val weightDownloads: Double = DEFAULT_WEIGHT_DOWNLOADS,
) {
    init {
        require(weightRating.isFinite() && weightRating >= 0) {
            "weightRating must be finite and not negative, was $weightRating"
        }
        require(weightDownloads.isFinite() && weightDownloads >= 0) {
            "weightDownloads must be finite and not negative, was $weightDownloads"
        }
    }

    companion object {
        const val DEFAULT_WEIGHT_RATING = 1.0
        const val DEFAULT_WEIGHT_DOWNLOADS = 0.5

        val DEFAULT = RankingConfig()
    }
}
