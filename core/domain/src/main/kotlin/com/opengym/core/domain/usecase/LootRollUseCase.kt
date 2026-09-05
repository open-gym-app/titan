package com.opengym.core.domain.usecase

import com.opengym.core.domain.gamification.DropTableEvaluator
import com.opengym.core.domain.model.Collectible
import com.opengym.core.domain.model.DropSource
import com.opengym.core.domain.repository.CollectedItemRepository
import com.opengym.core.domain.repository.GamificationRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Rolls a finished session's drop table once, ever, and banks whatever came up.
 *
 * [DropTableEvaluator] decides *what* a trigger drops and knows nothing about triggers actually
 * happening; [CollectedItemRepository] stores drops and deduplicates nothing. This is the piece
 * between them — it owns what the trigger **means**, which is three decisions the other two
 * deliberately refuse to make: that a finished session rolls [DropSource.SESSION_FINISHED], that
 * it rolls it once, and that the drop is dated to the workout rather than to the roll. The
 * pipeline owns only where it happens in the order.
 *
 * ### The fence, and why this step has one at all
 *
 * Four of the cascade's six steps are idempotent by construction — see
 * [SessionCompletionPipeline]'s re-entry contract. A roll is not, and cannot be made so: it is a
 * decision taken from a random source, so re-entering the cascade for a session already
 * processed would roll again and hand out a second collectible. Nothing on the way down can
 * catch that afterwards either, because a second *legitimate* drop of the same collectible is a
 * normal thing to hold — the inventory is a bag rather than a set, which is exactly why
 * `collected_item` carries no unique index to lean on.
 *
 * So the fence sits over the roll, and it is XP's fence with a table of its own:
 * [GamificationRepository.claimSessionLoot], taken **before** anything is rolled. That chooses,
 * knowingly, a torn run that drops nothing over a crash-recovery boot that drops on every pass —
 * see [GamificationRepository.claimSessionXp] for the whole of the trade, which is the same one.
 * A claim taken *after* a successful write would leave the window it exists to close wide open
 * for the length of the write.
 *
 * The claim is spent whether or not anything drops, and that is the point rather than a leak: it
 * fences the *roll*, and four triggers in five roll nothing at all on the shipped rates
 * ([com.opengym.core.domain.gamification.DropRates]). A fence that only closed on a payout would
 * let a re-entered cascade roll again and again until one landed, which is a drop rate of one
 * per session for anybody whose device crashes.
 *
 * @property dropTable the engine, and the only place the odds live. Handed in rather than
 *   constructed here so a test can pin a sequence and a catalogue; production wires the shipped
 *   one, which is what its own defaults already build.
 * @property collectedItemRepository where a drop is written. Nothing is read back — what
 *   [SessionProgression] reports is the [Collectible] this rolled, not a row re-read from the
 *   store.
 * @property gamificationRepository the claim, and nothing else this uses. It sits beside the XP
 *   claim because both are markers over one cascade's re-entry rather than anything to do with
 *   the tables they protect.
 */
class LootRollUseCase(
    private val dropTable: DropTableEvaluator,
    private val collectedItemRepository: CollectedItemRepository,
    private val gamificationRepository: GamificationRepository,
) {

    /**
     * Rolls for [sessionId], writes what dropped, and returns it.
     *
     * @param sessionId the finished session, and the key the claim is taken under — so a
     *   recovery path re-entering with nothing but the row is fenced by the same marker a
     *   normal finish takes.
     * @param finishedAt when that session finished, stamped on the drop as its acquisition
     *   moment. The workout's own moment rather than a clock's, the rule
     *   [AchievementTriggerUseCase] and [QuestCompletionUseCase] both already follow: a cascade
     *   retried an hour later dates the drop to the training that earned it.
     * @return the collectible that dropped, or **null** — which is the ordinary answer, in two
     *   different ways that are deliberately not distinguished here. The table paid out nothing
     *   (four times in five), or this session had already been rolled for. Both mean the same
     *   thing to the caller: this run produced no drop.
     */
    suspend operator fun invoke(
        sessionId: Long,
        finishedAt: Instant,
    ): Collectible? =
        // Uncancellable as one piece, for the reason [QuestCompletionUseCase.complete] is: the
        // claim is what makes the roll unrepeatable, so a cancellation landing between it and
        // the write would throw away a collectible the user had earned and no later run could
        // roll it again. A caller cancelled before this is entered is not affected — that is a
        // roll that never happened, and the claim is still there to be taken.
        withContext(NonCancellable) {
            if (!gamificationRepository.claimSessionLoot(sessionId)) return@withContext null

            val drop = dropTable.roll(DropSource.SESSION_FINISHED) ?: return@withContext null
            collectedItemRepository.recordDrop(
                collectibleId = drop.id,
                source = DropSource.SESSION_FINISHED.key,
                acquiredAt = finishedAt,
            )
            drop
        }
}
