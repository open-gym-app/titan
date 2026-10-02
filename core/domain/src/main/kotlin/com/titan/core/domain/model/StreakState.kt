package com.titan.core.domain.model

import java.time.Instant

/**
 * Everything the streak mechanic remembers between sessions.
 *
 * One value rather than four arguments, because it is what
 * [com.titan.core.domain.gamification.StreakCalculator.advance] both takes and returns:
 * the rule reads the whole of the stored state and answers with the whole of the next one,
 * and [com.titan.core.domain.repository.GamificationRepository.updateStreak] writes what it
 * is handed without deciding anything. That is what keeps the streak rule — the grace window,
 * the same-day guarantee, the protection allowance and its monthly rollover — stated once, in
 * the domain, rather than half in a calculator and half in whatever store happens to persist
 * it.
 *
 * The defaults are the state of a user who has never trained, and they are what a store hands
 * the rule when its table is empty, so the first session of a lifter's life is an ordinary
 * transition rather than a special case.
 *
 * @property currentStreak consecutive days trained, per
 *   [com.titan.core.domain.gamification.StreakCalculator].
 * @property longestStreak the best [currentStreak] ever held. Never falls: a break lowers the
 *   current streak and leaves the best one standing, which is the only reason it is stored
 *   separately rather than derived.
 * @property lastCountedAt when the session the streak was last *counted* from finished, or
 *   null for a user with no history. Deliberately not "when the last session finished" — a
 *   second session on an already-counted day leaves it where it is, which is what keeps the
 *   grace window from sliding forward with every workout of a two-a-day.
 * @property protection the monthly allowance that saves a streak a missed day would otherwise
 *   have broken. See [StreakProtection].
 */
data class StreakState(
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val lastCountedAt: Instant? = null,
    val protection: StreakProtection = StreakProtection(),
)
