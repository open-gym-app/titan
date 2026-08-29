package com.opengym.core.domain.model

/**
 * What one finished session did to the user's streak.
 *
 * Three outcomes rather than a bare `Int`, because the number alone cannot tell the two
 * cases the surfaces care about apart: a streak that went from 6 to 1 and a streak that was
 * 1 and stayed 1 store the same figure, and only one of them is a streak the user just lost.
 * [com.opengym.core.domain.gamification.StreakCalculator] decides which of these a session
 * produced; storing the result is the cascade's job (#145).
 *
 * ### Why [Reset] is its own case
 *
 * #106 adds streak protection — a monthly allowance that saves a streak the user would
 * otherwise have broken. That mechanic is exactly "intercept a [Reset] and decide whether to
 * spend an allowance on it", which is only possible while the break is a value somebody can
 * pattern-match on. Folding it into `Incremented(1)` would leave #106 re-deriving the break
 * from the gap it was handed, which is the calculator's rule reimplemented in a second place.
 */
sealed interface StreakResult {

    /**
     * The streak to store once this result is applied, given the [currentStreak] it was
     * computed against.
     *
     * Here rather than at each call site because [Unchanged] carries no figure of its own —
     * "still whatever it was" is only an answer relative to what it was — and a caller that
     * had to remember that would eventually store a zero for it.
     */
    fun streakAfter(currentStreak: Int): Int

    /**
     * The session extended the streak: [newStreak] is one more than the streak before it.
     *
     * Also the first session of a user's life, which extends a streak of nothing to a streak
     * of one — see [com.opengym.core.domain.gamification.StreakCalculator.onSessionFinished].
     */
    data class Incremented(val newStreak: Int) : StreakResult {
        override fun streakAfter(currentStreak: Int): Int = newStreak
    }

    /**
     * The session came too late to extend the streak, so it starts a new one.
     *
     * [newStreak] is [FIRST_DAY] and not zero: the session that broke the streak is itself
     * the first of the replacement, which is the difference between a user who lapsed and
     * trained today and a user who has not trained at all.
     */
    data object Reset : StreakResult {

        /** Always [FIRST_DAY] — see the doc above for why a break does not store a zero. */
        val newStreak: Int = FIRST_DAY

        override fun streakAfter(currentStreak: Int): Int = newStreak
    }

    /**
     * The session did not move the streak: a second session on a day the streak has already
     * been counted for, or one finished before the session the streak was last counted from.
     *
     * A no-op rather than an increment, so a lifter who splits their day into two workouts
     * does not accumulate streak twice as fast as one who trains once — the streak measures
     * days shown up, not sessions logged, the same distinction
     * [com.opengym.core.domain.usecase.ConsistencyUseCase] draws.
     */
    data object Unchanged : StreakResult {
        override fun streakAfter(currentStreak: Int): Int = currentStreak
    }

    companion object {

        /** Where any streak starts, whether it is the user's first or their fifth. */
        const val FIRST_DAY = 1
    }
}
