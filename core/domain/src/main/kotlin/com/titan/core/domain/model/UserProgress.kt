package com.titan.core.domain.model

import com.titan.core.domain.gamification.XpCalculator
import java.time.Instant

/**
 * Where the user stands: the points they have banked, the level those buy, and the streak
 * they are on.
 *
 * The read side of everything #33 and #34 compute and #35 stores. Every write to that row
 * goes through [com.titan.core.domain.repository.GamificationRepository]'s atomic
 * operations and answers with what *changed* — an [XpAward], a [StreakUpdate]. This is the
 * other question, and the one a screen asks: not "what did that session do" but "what is
 * true now".
 *
 * ### The level is stored, not re-derived here
 *
 * [level] is the column, written beside [totalXp] inside the same award that produced it — see
 * [com.titan.core.domain.repository.GamificationRepository.addXp], which derives both from
 * [XpCalculator] in one operation precisely so they cannot disagree. Recomputing it here would
 * be a second answer to a question already answered, and the two would diverge the moment
 * the curve is retuned: the stored level is what the user was told they reached.
 *
 * The *derived* figures below are a different matter. [xpIntoLevel] and [xpForNextLevel] are
 * arithmetic on [totalXp] against the curve, not stored facts, and they read the curve as it is
 * now — which is right, because they describe the climb still ahead.
 *
 * ### The defaults are the zero state, not a substitute for the null row
 *
 * `observeProgress` emits **null** while nothing has ever been banked, and that stays the
 * store's answer: #110 draws no level header at all in that case, which a zeroed row could not
 * express. The defaults here are for the screens that *do* have something to draw in that
 * state — the quest log opens on level 1 and an invitation to start a streak (#104) — so they
 * substitute this instance for the null themselves rather than each spelling the zeroes out.
 *
 * @property totalXp lifetime points, only ever added to.
 * @property level the level [totalXp] buys, as stored — see above.
 * @property currentStreak consecutive weeks that met the weekly goal **as of the last counted
 *   session**. It is not re-derived by the passage of time, so a streak that has since lapsed
 *   still reads at its old length here — [lastCountedAt] and [weekGoalMet] are what settle that,
 *   through [com.titan.core.domain.gamification.StreakCalculator.isLive]. A surface that draws
 *   this figure without asking is a surface that tells somebody who last trained in March that
 *   they are twelve weeks deep.
 * @property longestStreak the best [currentStreak] ever reached. At least [currentStreak], and
 *   equal to it while the user is on their best run.
 * @property lastCountedAt when the last session that counted towards the streak finished, or
 *   null for a user with no streak history — see
 *   [com.titan.core.domain.gamification.StreakCalculator].
 * @property weekGoalMet whether the week [lastCountedAt] falls in has met its goal — see
 *   [StreakState.weekGoalMet].
 */
data class UserProgress(
    val totalXp: Long = 0,
    val level: Int = XpCalculator.FIRST_LEVEL,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val lastCountedAt: Instant? = null,
    val weekGoalMet: Boolean = false,
) {

    /**
     * Points earned since [level] was reached — the numerator of the ring.
     *
     * Clamped into its own denominator for the reason [levelFraction] is: [level] and [totalXp]
     * are two stored values that a retuned curve can put out of step, and a screen printing the
     * raw subtraction would draw `-1,160 / 200 XP` under a wreath that is correctly empty.
     */
    val xpIntoLevel: Long
        get() = (totalXp - XpCalculator.xpRequiredForLevel(level)).coerceIn(0, xpForNextLevel.coerceAtLeast(0))

    /** Points from [level] to the next — the denominator of the ring. */
    val xpForNextLevel: Long
        get() = XpCalculator.xpToNextLevel(level).toLong()

    /**
     * How far into the current level, `0f..1f`.
     *
     * Clamped, because [level] is a stored value and [totalXp] is another: an award that
     * banked points and whose level write is a moment behind, or a curve retuned under a row
     * written by the old one, can put the numerator outside its denominator. A ring that wrapped
     * past the top or went negative would be a worse answer than a full or empty one.
     */
    val levelFraction: Float
        get() = when {
            xpForNextLevel <= 0 -> 1f
            else -> (xpIntoLevel.toFloat() / xpForNextLevel).coerceIn(0f, 1f)
        }
}
