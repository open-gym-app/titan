package com.opengym.core.domain.model

/**
 * A streak as it stands after one finished session was applied to it.
 *
 * [StreakResult] says what the session *did*; it does not say what the streak now is, because
 * [StreakResult.Unchanged] carries no figure of its own and [StreakResult.streakAfter] needs
 * the streak it was computed against to answer. Pairing the two is
 * [com.opengym.core.domain.gamification.StreakCalculator.advance]'s job, and this is what it
 * answers with — the same value
 * [com.opengym.core.domain.repository.GamificationRepository.updateStreak] returns, because
 * the store writes the rule's answer rather than composing one of its own.
 *
 * The pair is what the cascade needs on both counts: [days] is the figure
 * [AchievementProgress.currentStreak] is built from, and [result] is what a surface pattern-
 * matches on to tell a streak that was just lost from one that was never long — see
 * [StreakResult]'s own doc for why that distinction is a type rather than a number.
 *
 * @property result what the session did to the streak.
 * @property state the whole of the streak state after it, which is what the store persists.
 */
data class StreakUpdate(
    val result: StreakResult,
    val state: StreakState,
) {

    /**
     * The streak held after the session, always at least [StreakResult.FIRST_DAY] for a
     * session that counted.
     */
    val days: Int get() = state.currentStreak

    /**
     * What is left of this month's protection allowance.
     *
     * Readable on every update rather than only on a [StreakResult.Protected] one, because
     * the allowance is a standing figure a surface shows whether or not this session moved it
     * — and because the rollover into a new month is applied on every transition, so an
     * update that spent nothing can still be the one that refilled the count.
     */
    val protectionsRemaining: Int get() = state.protection.remaining

    /**
     * Whether this session's break was paid for out of the allowance — the fact the quest log
     * (#104) reads and #39's notification announces.
     */
    val protectionConsumed: Boolean get() = result is StreakResult.Protected
}
