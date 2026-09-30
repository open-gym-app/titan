package com.titan.core.domain.usecase

import com.titan.core.domain.model.QuestCompletion
import com.titan.core.domain.model.QuestProgress
import com.titan.core.domain.model.XpEvent
import com.titan.core.domain.repository.QuestRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset

/**
 * Completes every quest a finished session newly met, and pays each one's XP — never twice.
 *
 * The quest twin of [AchievementTriggerUseCase], and deliberately the same shape: the catalogue
 * is data, so there is no per-quest branch here and none is gained when the set grows. What it
 * owns is the *once*, which is the same two problems, with one extra wrinkle.
 *
 * ### Not completing twice
 *
 * A quest's target stays met for the rest of its period — a user who trained three times on
 * Wednesday still satisfies "three sessions this week" on Friday and Saturday. So the board is
 * filtered against what is already on file before anything is written, and the write itself is
 * insert-if-absent. The read is the cheap filter and the insert is the guarantee, exactly as in
 * [AchievementTriggerUseCase]: only the call that wrote the row is told it completed anything.
 *
 * ### Not paying twice
 *
 * [AwardXpUseCase] banks what it is handed and deduplicates nothing, so the award sits *after*
 * the insert and behind its answer. That buys **at most** once rather than exactly once — a
 * store that fails the award after the insert commits leaves the quest banked and its XP owed
 * forever, since every later run gets `false` back. Cancellation is closed off ([complete] runs
 * the pair uncancellably); a failing store is not, and cannot be from this layer. It is the same
 * trade [AchievementTriggerUseCase] names, for the same reason, and it closes properly the same
 * way: one transaction over the row and the XP.
 *
 * ### The extra wrinkle: which period
 *
 * An achievement is unlocked once, forever, so "when" only decides its timestamp. A quest is
 * completed *into a period*, and getting that wrong pays it out of the wrong week. The period
 * is therefore taken from [finishedAt] — the workout's own moment — rather than a clock, so a
 * session finished late on a Sunday and processed after a crash on the Monday completes the
 * week it was trained in. See [QuestProgressUseCase.at].
 *
 * ### Where the figures come from
 *
 * Read here, not passed in, which is the one place this differs from
 * [AchievementTriggerUseCase]. An [com.titan.core.domain.model.AchievementProgress] is three
 * lifetime scalars that the cascade has just recomputed and holds at one consistent moment; a
 * quest board is six period-bounded aggregates that nothing upstream has any reason to know.
 * Passing them in would put those six reads in the pipeline for the benefit of one step.
 *
 * **This must run after PR detection**, for the same reason achievements must: a quest that
 * completes on a record cannot see one that has not been written yet. It is ordered after
 * achievements rather than beside them only because [SessionProgressionStep] states one order
 * and quests have no claim on going first.
 */
class QuestCompletionUseCase(
    private val questProgress: QuestProgressUseCase,
    private val questRepository: QuestRepository,
    private val awardXp: AwardXpUseCase,
) {

    /**
     * Completes whatever the session newly met, in catalogue order, and returns the completions
     * that happened **here** — empty on the ordinary session that finished nothing, and empty on
     * a re-entry.
     *
     * @param finishedAt when the qualifying session finished. Decides both the period completed
     *   into and the timestamp stamped on the row; see the class doc.
     * @param into where each completion lands **as it happens**, and what is returned. The rows
     *   are written one at a time and each insert is what makes that quest unrepeatable for the
     *   period, so a store that refuses the third one's award has still completed and paid for
     *   two — and a caller that lost them to the throw could never recover them, since every
     *   later run gets `false` back from the insert. Defaulted, so a caller that does not care
     *   how far a failure got reads the return value and ignores this.
     */
    suspend operator fun invoke(
        finishedAt: Instant,
        into: MutableList<QuestCompletion> = mutableListOf(),
    ): List<QuestCompletion> {
        val date = finishedAt.atZone(ZoneOffset.UTC).toLocalDate()
        // One emission of the board, not a subscription: this runs inside a finishing session
        // and wants the figures as they stand now.
        val board = questProgress.at(date).first()

        board
            .filter { !it.isComplete && it.quest.isMetBy(it.progress) }
            .forEach { row -> complete(row, finishedAt)?.let { into += it } }
        return into
    }

    /** The completion, or null when the row was already there — see the class doc. */
    private suspend fun complete(
        row: QuestProgress,
        finishedAt: Instant,
    ): QuestCompletion? =
        // Uncancellable as one piece, for the reason [AchievementTriggerUseCase.unlock] is: the
        // insert is what makes the completion unrepeatable, so a cancellation landing between it
        // and the award would owe that XP until the period rolls over — and the period rolling
        // over does not repay it, it just moves on.
        withContext(NonCancellable) {
            val written = questRepository.completeQuest(
                questId = row.quest.id,
                periodStart = row.periodStart,
                completedAt = finishedAt,
            )
            if (!written) return@withContext null

            QuestCompletion(
                quest = row.quest,
                periodStart = row.periodStart,
                completedAt = finishedAt,
                xp = awardXp(XpEvent.QuestCompleted(row.quest.id, row.quest.xpReward)),
            )
        }
}
