package com.titan.core.domain.repository

import com.titan.core.domain.model.GymMilestone

/**
 * The record of which one-time gym milestones have already paid out.
 *
 * [GamificationRepository.claimSessionXp]'s sibling: XP is banked by whatever it is handed,
 * so the fence against paying twice has to live in a store that remembers.
 */
interface GymMilestoneRepository {

    /**
     * Claims [milestone] and returns whether **this call** claimed it.
     *
     * `true` only when no claim was on file; `false` for every call after the first. The check
     * and the write are one atomic operation, so two concurrent triggers cannot both be told
     * they may pay.
     */
    suspend fun claim(milestone: GymMilestone): Boolean
}
