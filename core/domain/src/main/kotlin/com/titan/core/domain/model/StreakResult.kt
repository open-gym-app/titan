package com.titan.core.domain.model

/**
 * What one finished session did to the user's streak.
 *
 * Cases rather than a bare `Int`, because the number alone cannot tell apart the situations the
 * surfaces care about: a streak that went from 6 to 0 and a streak that was 0 and stayed 0
 * store the same figure, and only one of them is a streak the user just lost.
 * [com.titan.core.domain.gamification.StreakCalculator] decides which of these a session
 * produced; storing the result is the cascade's job (#145).
 *
 * ### Why [Reset] is its own case
 *
 * #106 added streak protection — a monthly allowance that saves a streak the user would
 * otherwise have broken. That mechanic is exactly "intercept a [Reset] and decide whether to
 * spend an allowance on it", which is only possible while the break is a value somebody can
 * pattern-match on. [Protected] is the interception, and it is a case of its own rather than an
 * [Incremented] with a flag beside it because what a surface does about a saved streak is
 * nothing like what it does about an ordinary week.
 */
sealed interface StreakResult {

    /**
     * The streak to store once this result is applied, given the [currentStreak] it was
     * computed against.
     *
     * Here rather than at each call site because [Counted] and [Unchanged] carry no figure of
     * their own — "still whatever it was" is only an answer relative to what it was — and a
     * caller that had to remember that would eventually store a zero for it.
     */
    fun streakAfter(currentStreak: Int): Int

    /**
     * The session met the week's goal, so the week joins the streak: [newStreak] is one more
     * than the streak before it.
     *
     * Only the session that *reaches* the goal increments. The ones before it in the same week
     * are [Counted], and so are the ones after it, so a week is worth one whatever it held.
     */
    data class Incremented(val newStreak: Int) : StreakResult {
        override fun streakAfter(currentStreak: Int): Int = newStreak
    }

    /**
     * The session counted towards its week without moving the streak: the goal is not met
     * yet, or was already met earlier in the week.
     */
    data object Counted : StreakResult {
        override fun streakAfter(currentStreak: Int): Int = currentStreak
    }

    /**
     * The week before this session's fell short of the goal, or whole weeks went by without
     * training, so the streak is over and this session starts the next one.
     *
     * [newStreak] is zero in the ordinary case: the session that broke the streak opens a week
     * that has not met its goal yet. It is one when the goal is a single session, because then
     * this session meets it on its own.
     */
    data class Reset(val newStreak: Int) : StreakResult {
        override fun streakAfter(currentStreak: Int): Int = newStreak
    }

    /**
     * The streak would have broken, and a protection was spent to keep it — see
     * [StreakProtection].
     *
     * The covered week does not count: it is carried over rather than added, because it was
     * not a week the goal was met in, and a mechanic that paid for missed weeks would make the
     * streak a measure of how many protections a user had left. [newStreak] is therefore the
     * streak that was nearly lost, plus one when this session meets the goal of its own week
     * on its own.
     *
     * @property protectionsRemaining what is left of the month's allowance **after** this one
     *   was spent, which is the figure the quest log draws and the sentence #39's
     *   notification is written from ("0 protections left this month"). Carried on the result
     *   rather than re-read, because the read that produced it is the same atomic operation
     *   that spent the protection; anything re-reading it afterwards could see a later
     *   session's answer.
     */
    data class Protected(
        val newStreak: Int,
        val protectionsRemaining: Int,
    ) : StreakResult {
        override fun streakAfter(currentStreak: Int): Int = newStreak
    }

    /**
     * The session did not count at all: it finished at or before the session the streak was
     * last counted from.
     *
     * That is a re-run of the cascade for a session already counted, a backfill, or a device
     * clock that went backwards. None of them can extend a week already judged, and none of
     * them may count a session twice.
     */
    data object Unchanged : StreakResult {
        override fun streakAfter(currentStreak: Int): Int = currentStreak
    }
}
