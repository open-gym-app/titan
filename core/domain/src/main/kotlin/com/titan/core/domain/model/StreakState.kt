package com.titan.core.domain.model

import java.time.Instant

/**
 * Everything the streak mechanic remembers between sessions.
 *
 * One value rather than six arguments, because it is what
 * [com.titan.core.domain.gamification.StreakCalculator.advance] both takes and returns:
 * the rule reads the whole of the stored state and answers with the whole of the next one,
 * and [com.titan.core.domain.repository.GamificationRepository.updateStreak] writes what it
 * is handed without deciding anything. That is what keeps the streak rule — the weekly goal,
 * the anchor that makes a re-run count nothing, the protection allowance and its monthly
 * rollover — stated once, in the domain, rather than half in a calculator and half in whatever
 * store happens to persist it.
 *
 * The defaults are the state of a user who has never trained, and they are what a store hands
 * the rule when its table is empty, so the first session of a lifter's life is an ordinary
 * transition rather than a special case.
 *
 * @property currentStreak consecutive weeks in which the user met their weekly goal, per
 *   [com.titan.core.domain.gamification.StreakCalculator]. Includes the week [lastCountedAt]
 *   falls in only once that week's goal is met — see [weekGoalMet].
 * @property longestStreak the best [currentStreak] ever held. Never falls: a break lowers the
 *   current streak and leaves the best one standing, which is the only reason it is stored
 *   separately rather than derived.
 * @property lastCountedAt when the last session that counted towards the streak finished, or
 *   null for a user with no history. A session finished at or before it counts nothing, which
 *   is what makes a re-run of the cascade, or a backfilled session, harmless.
 * @property weekSessions the sessions counted in the week [lastCountedAt] falls in. Zero for a
 *   user with no history.
 * @property weekGoalMet whether that week's sessions have met the goal, and so whether
 *   [currentStreak] already includes it. Stored rather than re-derived from [weekSessions],
 *   because the goal is a setting that can change mid-week: what counts is the goal in force
 *   when the session was finished.
 * @property protection the monthly allowance that saves a streak a short week would otherwise
 *   have broken. See [StreakProtection].
 */
data class StreakState(
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val lastCountedAt: Instant? = null,
    val weekSessions: Int = 0,
    val weekGoalMet: Boolean = false,
    val protection: StreakProtection = StreakProtection(),
)
