package com.titan.core.domain.model

import java.time.Instant
import java.time.YearMonth

/**
 * The allowance that saves a streak the user would otherwise have broken, and what is left of
 * it this month.
 *
 * A streak is the one figure in the app that a single bad week destroys outright, and the rule in
 * [com.titan.core.domain.gamification.StreakCalculator] is deliberately unforgiving about it: a
 * week that falls short of the goal zeroes a count the user may have spent months building. That
 * is the right default — a streak nothing can break is not a streak — and it is also the mechanic
 * most likely to make somebody stop opening the app, because a lost streak is unrecoverable and
 * the loss lands in a week they already feel bad about. Protection is the release valve, and it
 * is *bounded* so the streak still means something: one a month, spent automatically, never
 * bankable.
 *
 * ### Why the allowance is monthly, and what "monthly" means
 *
 * The calendar month, in [com.titan.core.domain.gamification.StreakCalculator.STREAK_ZONE]
 * — the same UTC boundary the streak's weeks are cut at, with the same approximation written
 * down there. A rolling thirty-day window would be fairer and is not worth it: the
 * user has to be able to answer "how many do I have left" without the app explaining a window
 * to them, and "one a month" is a sentence that needs no explanation.
 *
 * Replenishment is **lazy** rather than scheduled — see [replenishedFor]. There is no job that
 * wakes at midnight on the first, because there does not need to be: nothing can spend a
 * protection except a finished session, and every path that reads or spends one applies the
 * rollover first. A scheduled write would add a background task whose only job is to make a
 * number correct that is already correct whenever anybody looks at it.
 *
 * Unused protections do **not** carry over. One saved week a month is a safety net; a year's
 * worth banked into twelve is a different app.
 *
 * @property remaining how many are left in [period], never negative and never above
 *   [MONTHLY_ALLOWANCE] for a state that has been through [replenishedFor].
 * @property period the month [remaining] belongs to, or **null** for a user whose allowance
 *   has never been touched. Null rather than "the current month" because the model has no
 *   clock: a fresh state is full whatever month it is first read in, and the first
 *   [replenishedFor] stamps the month it was read in.
 * @property lastConsumedAt when a protection was last spent, or null if none ever has been.
 *   A record of the last spend, stored and carried through a backup, rather than a part of the
 *   mechanic: the recap and the progression notification report a spend from the
 *   [StreakUpdate] that made it, not from this. Idempotency does **not** rest on it either; see
 *   [com.titan.core.domain.gamification.StreakCalculator.advance].
 */
data class StreakProtection(
    val remaining: Int = MONTHLY_ALLOWANCE,
    val period: YearMonth? = null,
    val lastConsumedAt: Instant? = null,
) {

    init {
        require(remaining >= 0) { "remaining cannot be negative, was $remaining" }
    }

    /** Whether there is anything left to spend this month. */
    val hasProtection: Boolean get() = remaining > 0

    /**
     * This allowance as it stands in [month] — refilled if [period] is behind it, untouched if
     * it is already that month.
     *
     * The whole of the replenishment rule, and the reason no scheduled job exists. Pure, so
     * both the write path (a session being applied) and the read path (a screen rendering the
     * count) can apply it from their own moment without a second copy of the boundary.
     *
     * A [period] in the *future* — a device whose clock went backwards, a state written on a
     * phone set forward — refills too. The alternative is a user stuck at zero protections
     * until the clock catches up, which is a worse answer to a state that should not exist
     * than an extra refill nobody can engineer on purpose. That is the read path's answer,
     * where [month] is the month somebody is looking at the count in.
     *
     * The write path guards the one case this cannot see for itself: a session finished
     * *before* the streak's anchor never asks — see
     * [com.titan.core.domain.gamification.StreakCalculator.advance]. Rolling backwards onto
     * that session's month would refill an allowance a later month has already spent from,
     * which hands back a protection rather than un-sticking a count.
     *
     * A count above [MONTHLY_ALLOWANCE] in the current month — a row written when the allowance
     * was larger, or a restored backup of one — is cut down to it, so the allowance is the most
     * anybody can spend in a month whatever the stored row says.
     */
    fun replenishedFor(month: YearMonth): StreakProtection =
        when {
            period != month -> copy(remaining = MONTHLY_ALLOWANCE, period = month)
            remaining > MONTHLY_ALLOWANCE -> copy(remaining = MONTHLY_ALLOWANCE)
            else -> this
        }

    /**
     * This allowance with one protection spent at [at].
     *
     * @throws IllegalStateException if nothing is left. Spending is only ever decided behind
     *   [hasProtection], so a call with none remaining is a caller that skipped the check
     *   rather than a user who ran out.
     */
    fun consumedAt(at: Instant): StreakProtection {
        check(hasProtection) { "no protection left to consume in $period" }
        return copy(remaining = remaining - 1, lastConsumedAt = at)
    }

    companion object {

        /**
         * How many protections a month holds.
         *
         * One: a week a month is what an ordinary bad patch costs — an illness, a work trip —
         * and more than that would let a lifter who meets their goal every other week hold a
         * streak on protections alone, since a month has only four or five weeks to judge. Like
         * [com.titan.core.domain.gamification.StreakCalculator.MIN_PROTECTED_STREAK] it is a
         * starting default that has not been validated against real usage, and retuning it is
         * an edit to this line.
         */
        const val MONTHLY_ALLOWANCE = 1
    }
}
