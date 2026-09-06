package com.opengym.core.domain.repository

import com.opengym.core.domain.model.CollectedItem
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * What the user has actually been dropped — the collectible inventory.
 *
 * Two members, and narrow on purpose: a method appears here when something exists to call it,
 * the principle [PersonalRecordRepository] was built on and [GamificationRepository] restates.
 * #171 is what will call [recordDrop], at the one point a roll happens; #104's quest log is
 * what collects [observeRecent]. There is deliberately no "do I hold this one" and no count —
 * nothing asks, and a collectible that does something is a milestone of its own (see #107).
 *
 * ### An inventory, not a set
 *
 * [GamificationRepository.unlockAchievement] answers whether *this* call unlocked something,
 * because an unlock happens once and that answer is what XP is paid on. Nothing of the sort
 * applies here: a collectible can drop again, so every recorded drop is a new row and there is
 * no boolean to return. What stops a re-entered cascade rolling twice is a fence over the
 * *roll*, which is #171's and not this table's — see `SessionCompletionPipeline`.
 */
interface CollectedItemRepository {

    /**
     * The most recent [limit] drops, newest first.
     *
     * A `Flow` for the reason [QuestRepository.observeCompletedIds] is one: a quest log is open
     * while a session finishes behind it, and a drop earned in that moment must fill in rather
     * than waiting for the screen to be left and come back.
     *
     * Bounded rather than the whole inventory, the same call `observeCompletedIds` makes by
     * scoping itself to one period: this table grows for the life of an install and never
     * shrinks, and its one reader draws a recent-drops section rather than a full collection.
     *
     * @param limit how many drops to return, at least 1. A screen's figure rather than a
     *   default here, so the domain carries no opinion about how much of a list fits.
     * @throws IllegalArgumentException if [limit] is under 1, and **from this call** rather
     *   than from the returned flow: SQLite reads a negative `LIMIT` as no limit at all, so a
     *   figure computed from an empty screen would otherwise hand back the whole inventory.
     *   A caller that builds the flow in a property initialiser and wraps it in `catch` still
     *   sees the throw, because it happens before there is a flow to catch it.
     */
    fun observeRecent(limit: Int): Flow<List<CollectedItem>>

    /**
     * Writes down that [collectibleId] dropped, from [source], at [acquiredAt].
     *
     * Always a new row. Two calls naming the same collectible leave two drops, which is what
     * makes this an inventory rather than a set — see [CollectedItem].
     *
     * @param collectibleId the catalogue's stable key for what dropped. Not validated against
     *   the catalogue: a drop is written by the engine that just rolled it, and a store that
     *   second-guessed the id would be the thing standing between a newer build's collectible
     *   and the restore that has to preserve it.
     * @param source the stable key of the trigger that produced the drop.
     * @param acquiredAt when the triggering event **happened**, not when this is called: a
     *   cascade retried an hour later must date the drop to the workout that earned it, the
     *   rule [QuestRepository.completeQuest] and
     *   [GamificationRepository.unlockAchievement] both already follow.
     */
    suspend fun recordDrop(
        collectibleId: String,
        source: String,
        acquiredAt: Instant,
    )
}
