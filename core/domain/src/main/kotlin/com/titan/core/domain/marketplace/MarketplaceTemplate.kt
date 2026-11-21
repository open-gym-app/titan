package com.titan.core.domain.marketplace

import kotlin.time.Duration

/**
 * A workout template as the community marketplace lists it.
 *
 * Deliberately unrelated to [com.titan.core.domain.model.WorkoutTemplate]: no shared
 * supertype, no shared fields by reference. A marketplace listing is someone else's
 * published copy with its own identity (a string id from the catalogue, not a local row
 * id), its own audience figures and no exercise body. Importing one into the user's own
 * templates is a translation step, not an upcast, so the two can evolve apart.
 *
 * @property id the catalogue's identifier, stable across fetches.
 * @property tags free-form labels the author attached — muscle groups (`chest`), equipment
 *   (`barbell`) and anything else. Matched case-insensitively by [MarketplaceFilter].
 * @property downloadCount how many times the template was imported. Never negative.
 * @property averageRating mean star rating, from [MIN_RATING] to [MAX_RATING].
 * @property estimatedDuration how long one session of it takes, for the duration filter.
 *   Not in the field list the marketplace issue gave, which asks for a duration filter
 *   but gives the model nothing to filter on.
 * @property createdAt publication time, epoch milliseconds UTC like every other
 *   timestamp in the domain.
 */
data class MarketplaceTemplate(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val downloadCount: Long,
    val averageRating: Double,
    val estimatedDuration: Duration,
    val createdAt: Long,
) {
    init {
        require(downloadCount >= 0) { "downloadCount must not be negative, was $downloadCount" }
        require(averageRating in MIN_RATING..MAX_RATING) {
            "averageRating must be in $MIN_RATING..$MAX_RATING, was $averageRating"
        }
        require(!estimatedDuration.isNegative()) {
            "estimatedDuration must not be negative, was $estimatedDuration"
        }
    }

    /** [tags] trimmed and lowercased once, for the tag filters to match against. */
    internal val normalizedTags: Set<String> by lazy { tags.mapTo(HashSet()) { it.normalizedTag() } }

    companion object {
        const val MIN_RATING = 0.0
        const val MAX_RATING = 5.0
    }
}
