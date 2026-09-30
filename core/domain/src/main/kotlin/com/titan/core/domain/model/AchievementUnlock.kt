package com.titan.core.domain.model

import java.time.Instant

/**
 * An achievement that was just unlocked, and the XP that unlocking it paid.
 *
 * Returned by [com.titan.core.domain.usecase.AchievementTriggerUseCase] so the cascade
 * (#145) can hand the summary screen (#111) everything it needs to celebrate without a
 * follow-up query — including [XpAward.leveledUp], which is only trustworthy as the value
 * produced inside the write that banked the points.
 *
 * One of these means the unlock *happened here*, on this call. An achievement the user
 * already held is not in the list at all, which is what makes "did anything unlock" a check
 * on emptiness rather than a diff against what was stored.
 *
 * @property achievement which milestone, catalogue entry and all.
 * @property unlockedAt when it was earned — the session's finish, not the moment the row was
 *   written, for the same reason [PersonalRecord.achievedAt] is the set's timestamp: a
 *   cascade retried an hour later must not date the unlock to the retry.
 * @property xp what the unlock was worth and where it left the user.
 */
data class AchievementUnlock(
    val achievement: Achievement,
    val unlockedAt: Instant,
    val xp: XpAward,
)
