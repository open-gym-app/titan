package com.titan.core.domain.gym

import com.titan.core.domain.model.GymReview
import com.titan.core.domain.model.ReviewError
import com.titan.core.domain.repository.GymReviewRepository
import com.titan.core.domain.time.Clock

/**
 * Saves the user's review of a gym and pays the first-review XP.
 *
 * Refused with [ReviewError.InvalidRating] when [rating] is outside 1–5, with
 * [ReviewError.CommentTooLong] when the comment is over 500 characters, and with
 * [ReviewError.AlreadyReviewed] when the gym already has a review. Nothing is written, and no
 * XP is paid, unless all three pass.
 *
 * The comment is trimmed, and a blank one is stored as null: "no comment" has one
 * representation, and the length limit applies to what is kept.
 *
 * Whether [gymId] is a saved gym is not checked here: the gym directory is a separate
 * repository this use case has no reason to depend on, and a screen can only offer the form
 * for a gym it is showing.
 */
class AddReviewUseCase(
    private val reviews: GymReviewRepository,
    private val xpHook: GymXpHook,
    private val clock: Clock = Clock.SYSTEM,
) {

    suspend operator fun invoke(
        gymId: Long,
        rating: Int,
        comment: String? = null,
    ): Result<GymReview> {
        if (rating !in GymReview.MIN_RATING..GymReview.MAX_RATING) {
            return Result.failure(ReviewError.InvalidRating(rating))
        }
        val trimmed = comment?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null && trimmed.length > GymReview.MAX_COMMENT_LENGTH) {
            return Result.failure(ReviewError.CommentTooLong(trimmed.length))
        }

        val review = GymReview(gymId, rating, trimmed, clock.nowMillis())
        if (!reviews.insertIfAbsent(review)) {
            return Result.failure(ReviewError.AlreadyReviewed(gymId))
        }
        xpHook.onReviewPosted()
        return Result.success(review)
    }
}
