package com.titan.core.domain.model

/**
 * Every way a gym review can be refused.
 *
 * Returned inside a [Result] by [com.titan.core.domain.gym.AddReviewUseCase] rather than
 * thrown, mirroring [TemplateError]: a caller `when`s over the failure and renders it against
 * the offending field.
 */
sealed class ReviewError(message: String) : IllegalArgumentException(message) {

    /** The rating was outside [GymReview.MIN_RATING]..[GymReview.MAX_RATING]. */
    data class InvalidRating(val rating: Int) :
        ReviewError("a rating must be between ${GymReview.MIN_RATING} and ${GymReview.MAX_RATING}, was $rating")

    /** The comment, trimmed, is longer than [GymReview.MAX_COMMENT_LENGTH]. */
    data class CommentTooLong(val length: Int) :
        ReviewError("a comment is at most ${GymReview.MAX_COMMENT_LENGTH} characters, was $length")

    /** This gym already has a review; there is one per gym, and it is not replaced. */
    data class AlreadyReviewed(val gymId: Long) : ReviewError("gym $gymId has already been reviewed")
}
