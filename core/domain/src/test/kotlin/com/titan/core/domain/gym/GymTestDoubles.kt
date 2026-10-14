package com.titan.core.domain.gym

import com.titan.core.domain.model.GymMilestone
import com.titan.core.domain.model.GymReview
import com.titan.core.domain.repository.GymMilestoneRepository
import com.titan.core.domain.repository.GymReviewRepository

class FakeGymReviewRepository : GymReviewRepository {

    val stored = linkedMapOf<Long, GymReview>()

    override suspend fun insertIfAbsent(review: GymReview): Boolean {
        if (review.gymId in stored) return false
        stored[review.gymId] = review
        return true
    }
}

class FakeGymMilestoneRepository : GymMilestoneRepository {

    private val claimed = mutableSetOf<GymMilestone>()

    override suspend fun claim(milestone: GymMilestone): Boolean = claimed.add(milestone)
}
