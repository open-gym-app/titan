package com.opengym.core.domain.model

/**
 * A streak as it stands after one finished session was applied to it.
 *
 * [StreakResult] says what the session *did*; it does not say what the streak now is, because
 * [StreakResult.Unchanged] carries no figure of its own and [StreakResult.streakAfter] needs
 * the streak it was computed against to answer. Only the store holds that, so only the store
 * can pair the two — which is why this comes back from
 * [com.opengym.core.domain.repository.GamificationRepository.updateStreak] rather than being
 * assembled by its caller.
 *
 * The pair is what the cascade needs on both counts: [days] is the figure
 * [AchievementProgress.currentStreak] is built from, and [result] is what a surface pattern-
 * matches on to tell a streak that was just lost from one that was never long — see
 * [StreakResult]'s own doc for why that distinction is a type rather than a number.
 *
 * @property result what the session did to the streak.
 * @property days the streak held after it, always at least [StreakResult.FIRST_DAY] for a
 *   session that counted.
 */
data class StreakUpdate(
    val result: StreakResult,
    val days: Int,
)
