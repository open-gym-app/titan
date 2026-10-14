package com.titan.core.domain.repository

import com.titan.core.domain.model.GymReview

/**
 * Where gym reviews are kept — at most one per gym.
 *
 * Narrow on purpose, in the spirit of [GamificationRepository]: a method appears when
 * something calls it.
 */
interface GymReviewRepository {

    /**
     * Stores [review] unless its gym already has one, and returns whether **this call** wrote it.
     *
     * The check and the write are **one atomic operation**, for the reason
     * [GamificationRepository.unlockAchievement] gives: a caller that looked first and inserted
     * after would let two concurrent submissions both be told they were the first. An existing
     * review is left exactly as it was.
     */
    suspend fun insertIfAbsent(review: GymReview): Boolean
}
