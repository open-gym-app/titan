package com.opengym.core.domain.usecase

import com.opengym.core.domain.gamification.XpCalculator
import com.opengym.core.domain.model.XpAward
import com.opengym.core.domain.model.XpEvent
import com.opengym.core.domain.repository.GamificationRepository

/**
 * Prices one [XpEvent], banks it, and says whether it levelled the user up.
 *
 * The one way XP is awarded. Thin by design — the pricing is [XpCalculator]'s and the
 * atomicity is [GamificationRepository.addXp]'s — but it is what keeps those two joined:
 * a caller that added points directly could bank a figure the calculator does not agree
 * with, or store a total whose level was derived from a different curve.
 *
 * **What emits events and in what order is not this class's business.** #145 decides where
 * the awards for a finished session sit relative to PR detection and streaks; this only has
 * to be correct for whatever event it is handed, whenever it is handed one.
 *
 * ### Called once per event, never per batch
 *
 * A session with ten sets and a broken record is eleven-plus calls, not one — see [XpEvent]
 * for why bundling would spoil both the tuning and the level-up signal. The calls are cheap
 * (one small write each) and are serialised by the repository, so the total they leave
 * behind is the same whatever order they arrive in, and exactly one of them reports
 * [XpAward.leveledUp].
 *
 * Nothing here deduplicates: this awards what it is given, so an event emitted twice is
 * paid twice. Emitting each qualifying action once is the emitter's job — #34 owes that for
 * achievement unlocks, which are the case where a re-run could otherwise re-award.
 */
class AwardXpUseCase(private val gamificationRepository: GamificationRepository) {

    /**
     * Awards [event]'s points and returns where that left the user.
     *
     * The level is not computed here and compared with a stored one: [XpCalculator]'s curve
     * is handed to the repository and applied to the new total inside the same write, so the
     * answer cannot be read against a total that has since moved on.
     */
    suspend operator fun invoke(event: XpEvent): XpAward =
        gamificationRepository.addXp(
            delta = XpCalculator.xpFor(event),
            levelForTotalXp = XpCalculator::levelForTotalXp,
        )
}
