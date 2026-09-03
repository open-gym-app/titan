package com.opengym.core.domain.repository

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * What has been completed, per quest and per period.
 *
 * Only the completions. The *progress* behind them is not stored: it is the same sessions,
 * records and volume the progression screens already aggregate, and a second copy of those
 * figures would be one that could disagree with the first — see `QuestProgressUseCase`, which
 * reads the existing aggregates rather than a counter this would have to keep. What cannot be
 * re-derived is which quests were banked, because that is the thing XP was paid on and the
 * figures behind it can change afterwards; see [com.opengym.core.domain.model.QuestProgress].
 */
interface QuestRepository {

    /**
     * The quest ids completed in the period opening on [periodStart].
     *
     * A `Flow`, unlike [completedIdsOnce]: a board is open while a session finishes behind it,
     * and a quest that completes must fill in rather than waiting for the screen to be left and
     * come back.
     *
     * Keyed on the period's **first day** rather than its bounds, because that is what makes a
     * period identify itself — see
     * [com.opengym.core.domain.model.QuestPeriod.startOf]. A weekly and a monthly quest whose
     * periods both open on the 1st of a month that is a Monday share a key here and are still
     * distinguished by their id, which is the other half of the row's identity.
     */
    fun observeCompletedIds(periodStart: LocalDate): Flow<Set<String>>

    /**
     * Records [questId] as completed for the period opening on [periodStart], and answers
     * whether **this call** is the one that wrote it.
     *
     * The atomic half of "pay once". [com.opengym.core.domain.usecase.AwardXpUseCase] banks
     * whatever it is handed and deduplicates nothing, so not paying twice is the caller's job,
     * and a read-then-write could let two cascades interleave and both decide they were first.
     * This is one `INSERT OR IGNORE`, so exactly one caller is told `true` — the same guarantee
     * [GamificationRepository.unlockAchievement] gives, narrowed to a period.
     *
     * @param completedAt when the qualifying session **finished**, not when the row was
     *   written: a cascade retried an hour later must date the completion to the workout that
     *   earned it, the rule `PrDetectionUseCase` and `AchievementTriggerUseCase` both follow.
     * @return `true` when the completion was written here, `false` when it was already on file.
     */
    suspend fun completeQuest(
        questId: String,
        periodStart: LocalDate,
        completedAt: Instant,
    ): Boolean
}
