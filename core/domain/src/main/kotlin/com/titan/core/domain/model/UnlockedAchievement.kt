package com.titan.core.domain.model

import java.time.Instant

/**
 * An achievement the user holds, and when they earned it.
 *
 * Not [AchievementUnlock], which is what a *just-finished session* unlocked and carries the XP
 * that unlocking paid. This is the standing fact — one row of the `achievement` table — for a
 * grid that draws everything the user has ever reached, long after the award that paid for it.
 *
 * The catalogue entry is not carried. A grid draws every [Achievement] in
 * [com.titan.core.domain.gamification.Achievements] and marks which are held, so it needs the
 * *set* of ids rather than a join; and an id on file that matches no catalogue entry — a
 * milestone renamed without a migration — must not be able to put a row with no definition into
 * that list.
 *
 * @property achievementId the stable catalogue key, as stored.
 * @property unlockedAt when the milestone was earned — the finishing session's own moment.
 */
data class UnlockedAchievement(
    val achievementId: String,
    val unlockedAt: Instant,
)
