package com.titan.core.domain.model

/**
 * A workout template published to the marketplace for others to discover.
 *
 * Deliberately **not** a [WorkoutTemplate] and not a sibling of one: a marketplace listing is
 * what a stranger published (author, ratings, popularity), and a local template is what the
 * user owns and edits. Sharing a supertype would let either side's evolution leak into the
 * other; turning a listing into a local template is a conversion for whoever builds the
 * download flow.
 *
 * @property id the listing's stable key in the marketplace, a string because the marketplace
 *   assigns it rather than a local database.
 * @property tags free-form labels, among them the muscle groups and equipment the template
 *   uses; matched case-insensitively by [com.titan.core.domain.marketplace.MarketplaceFilter].
 * @property downloadCount how many times it has been downloaded, never negative.
 * @property averageRating the mean star rating, 0.0 to 5.0.
 * @property durationMinutes how long a session of it takes. Not in the issue's field list, but
 *   its search filters on a duration range and nothing else here says how long a template is.
 * @property createdAt epoch milliseconds, UTC.
 */
data class MarketplaceTemplate(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val downloadCount: Int,
    val averageRating: Double,
    val durationMinutes: Int,
    val createdAt: Long,
) {
    init {
        require(downloadCount >= 0) { "downloadCount cannot be negative, was $downloadCount" }
        require(averageRating in 0.0..MAX_RATING) { "averageRating must be within 0..5, was $averageRating" }
        require(durationMinutes > 0) { "durationMinutes must be positive, was $durationMinutes" }
    }

    private companion object {
        const val MAX_RATING = 5.0
    }
}
