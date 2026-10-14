package com.titan.core.domain.model

/**
 * The user's rating of one saved gym.
 *
 * Holds only what [com.titan.core.domain.gym.AddReviewUseCase] has already accepted, so a
 * value of this type is trusted to be in range by whoever reads it; the constraints are
 * stated once, in [MIN_RATING], [MAX_RATING] and [MAX_COMMENT_LENGTH], and checked at the
 * door rather than here so that a refusal is a [ReviewError] the caller can render instead of
 * an exception thrown from a constructor.
 *
 * @property gymId the gym reviewed. Also the review's identity: a user reviews a gym at most
 *   once, so no row id is needed to tell two apart.
 * @property rating whole stars, [MIN_RATING] to [MAX_RATING].
 * @property comment free text of at most [MAX_COMMENT_LENGTH] characters, or null for a rating
 *   with nothing to say.
 * @property createdAt epoch milliseconds, UTC.
 */
data class GymReview(
    val gymId: Long,
    val rating: Int,
    val comment: String? = null,
    val createdAt: Long,
) {
    companion object {
        const val MIN_RATING = 1
        const val MAX_RATING = 5
        const val MAX_COMMENT_LENGTH = 500
    }
}
