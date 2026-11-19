package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.DropTableEvaluator
import com.titan.core.domain.gamification.FoodDrop
import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.DropSource
import com.titan.core.domain.repository.CollectedItemRepository
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.PetRepository
import com.titan.core.domain.repository.SessionRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
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
 * ([com.titan.core.domain.gamification.DropRates]). A fence that only closed on a payout would
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
    private val petRepository: PetRepository? = null,
    private val sessionRepository: SessionRepository? = null,
    private val foodDrop: FoodDrop = FoodDrop(),
) {

    /** The trophy roll alone, as it has always been answered. See [roll] for the food. */
    suspend operator fun invoke(
        sessionId: Long,
        finishedAt: Instant,
    ): Collectible? = roll(sessionId, finishedAt).drop

    /**
     * Rolls the session's drops: the trophy roll, and — for a user who owns a titan — the food.
     *
     * One claim covers both, taken before either draw, so a re-entered cascade draws nothing.
     * The sets are read before the claim, so a read that fails leaves the claim for the re-run.
     * With no titan, or with no session store wired, the food draw is not made and the trophy
     * roll consumes exactly the numbers it always did.
     */
    suspend fun roll(
        sessionId: Long,
        finishedAt: Instant,
    ): LootRoll {
        val sets = if (petRepository != null && sessionRepository != null && petRepository.count() > 0) {
            sessionRepository.getSetsForSession(sessionId).first()
        } else {
            null
        }

        // Uncancellable as one piece, for the reason [QuestCompletionUseCase.complete] is: the
        // claim is what makes the roll unrepeatable, so a cancellation landing between it and
        // the writes would throw away what the user had earned and no later run could roll it.
        return withContext(NonCancellable) {
            if (!gamificationRepository.claimSessionLoot(sessionId)) return@withContext LootRoll.NOTHING

            val drop = dropTable.roll(DropSource.SESSION_FINISHED)
            drop?.let { record(it, sessionId, finishedAt) }
            val food = sets?.let(foodDrop::roll).orEmpty()
            food.forEach { record(it, sessionId, finishedAt) }
            LootRoll(drop = drop, food = food)
        }
    }

    private suspend fun record(
        collectible: Collectible,
        sessionId: Long,
        finishedAt: Instant,
    ) {
        collectedItemRepository.recordDrop(
            collectibleId = collectible.id,
            source = DropSource.SESSION_FINISHED.key,
            acquiredAt = finishedAt,
            sessionId = sessionId,
        )
    }
}

/**
 * What a session's roll produced.
 *
 * @property drop the trophy roll's answer, or null for the ordinary session that drops nothing.
 * @property food the food it paid, in the order drawn; empty with no titan to feed.
 */
data class LootRoll(
    val drop: Collectible?,
    val food: List<Collectible>,
) {

    companion object {

        /** A roll refused at the claim: nothing at all. */
        val NOTHING = LootRoll(drop = null, food = emptyList())
    }
}
