package com.titan.core.domain.gym

import com.titan.core.domain.model.GymMilestone
import com.titan.core.domain.model.XpAward
import com.titan.core.domain.model.XpEvent
import com.titan.core.domain.repository.GymMilestoneRepository
import com.titan.core.domain.usecase.AwardXpUseCase

/**
 * Pays the gym map's one-time XP awards — the first check-in and the first review — at most
 * once each, however often the triggering action happens.
 *
 * [AwardXpUseCase] banks whatever it is handed, so the "at most once" lives here: each
 * milestone is claimed in a [GymMilestoneRepository] and XP is emitted only by the call that
 * won the claim. The two milestones are independent of each other and of which gym triggered
 * them — the first check-in at a second gym pays nothing.
 *
 * The claim is taken **before** the award, choosing under-paying over double-paying if the
 * process dies in between — the same trade [com.titan.core.domain.repository.GamificationRepository.claimSessionXp]
 * documents, for the same reason.
 */
class GymXpHook(
    private val milestones: GymMilestoneRepository,
    private val awardXp: AwardXpUseCase,
) {

    /** Call when the user checks in at a gym. Returns the award, or null if already paid. */
    suspend fun onCheckIn(): XpAward? = award(GymMilestone.FIRST_CHECK_IN, XpEvent.GymFirstCheckIn)

    /** Call when a review has been posted. Returns the award, or null if already paid. */
    suspend fun onReviewPosted(): XpAward? = award(GymMilestone.FIRST_REVIEW, XpEvent.GymFirstReview)

    private suspend fun award(
        milestone: GymMilestone,
        event: XpEvent,
    ): XpAward? = if (milestones.claim(milestone)) awardXp(event) else null
}
