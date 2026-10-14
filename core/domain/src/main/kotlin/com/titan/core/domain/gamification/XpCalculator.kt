package com.titan.core.domain.gamification

import com.titan.core.domain.model.XpEvent

/**
 * What each [XpEvent] is worth, and what a lifetime total means as a level.
 *
 * Pure arithmetic over named constants — no repository, no clock — so the numbers below are
 * the only thing anybody has to touch to retune the economy, and a test can pin the curve
 * without a database. [com.titan.core.domain.usecase.AwardXpUseCase] is what turns these
 * answers into a stored total.
 *
 * ### The values are starting defaults
 *
 * The four award constants and [XP_PER_LEVEL] are first guesses, not validated against real
 * usage — flagged to product for tuning once there is any. Nothing reads a literal instead
 * of these, so tuning is an edit here and nowhere else; the relationships they encode are
 * the part worth arguing about: a set is the small, frequent drip, a session is worth five
 * sets, a record is worth two sessions, and an achievement is worth four sessions — so two
 * records, and a whole level on the curve below.
 *
 * ### A linear curve
 *
 * Level *n* costs the same additional XP as the level before it — `XP_PER_LEVEL × (n − 1)`
 * to reach level *n* at all, so level 1 is free, level 2 costs 100 and level 12 costs 1100.
 * Not the exponential curve most XP systems use: this one is a training log rather than a
 * game with an endgame to pace people towards, and a curve that stretches means a lifter
 * who trains just as hard in year two sees their bar move slower for it. Linear keeps "a
 * session is a fifth of a level" true forever.
 */
object XpCalculator {

    /** Points for one logged set — see [XpEvent.SetLogged]. */
    const val XP_PER_SET = 5

    /** Points for finishing a session — see [XpEvent.SessionFinished]. */
    const val XP_PER_SESSION = 25

    /** Points for one broken personal record — see [XpEvent.PrBroken]. */
    const val XP_PER_PR = 50

    /** Points for one unlocked achievement — see [XpEvent.AchievementUnlocked]. */
    const val XP_PER_ACHIEVEMENT = 100

    /** Points for the first gym check-in ever — see [XpEvent.GymFirstCheckIn]. */
    const val XP_PER_FIRST_GYM_CHECK_IN = 50

    /** Points for the first gym review ever — see [XpEvent.GymFirstReview]. */
    const val XP_PER_FIRST_GYM_REVIEW = 30

    /** The cost of every level, the whole of the curve — see the class doc. */
    const val XP_PER_LEVEL = 100

    /** The level a user starts at, holding no XP at all. */
    const val FIRST_LEVEL = 1

    /**
     * What [event] is worth.
     *
     * Exhaustive over [XpEvent] by construction: a source added later (a daily challenge,
     * a completed quest) fails to compile here until it is priced, which is the intended
     * failure mode — the alternative, an `else` branch, would quietly award a new source
     * nothing at all and look like a tuning value of zero rather than an oversight.
     *
     * Every [com.titan.core.domain.model.PrType] is worth the same today; the event carries
     * which one so that can stop being true without a change to the event model.
     */
    fun xpFor(event: XpEvent): Int =
        when (event) {
            XpEvent.SetLogged -> XP_PER_SET

            XpEvent.SessionFinished -> XP_PER_SESSION

            is XpEvent.PrBroken -> XP_PER_PR

            is XpEvent.AchievementUnlocked -> XP_PER_ACHIEVEMENT

            // The only variant that prices itself. A quest's reward is part of its definition
            // rather than a property of the kind, so it arrives on the event; see
            // [XpEvent.QuestCompleted], which carries the argument.
            is XpEvent.QuestCompleted -> event.xp

            XpEvent.GymFirstCheckIn -> XP_PER_FIRST_GYM_CHECK_IN

            XpEvent.GymFirstReview -> XP_PER_FIRST_GYM_REVIEW
        }

    /**
     * The level a lifetime total of [totalXp] points buys.
     *
     * [FIRST_LEVEL] at zero, one level up per [XP_PER_LEVEL] points thereafter, and a total
     * sitting exactly on a threshold is already at the level it pays for: 100 points is
     * level 2, 199 is still level 2, 200 is level 3.
     *
     * @throws IllegalArgumentException if [totalXp] is negative. XP is only ever added —
     *   nothing in the app spends it — so a negative total is a corrupt row rather than a
     *   state to render a level for.
     */
    fun levelForTotalXp(totalXp: Long): Int {
        require(totalXp >= 0) { "totalXp cannot be negative, was $totalXp" }
        return (totalXp / XP_PER_LEVEL).toInt() + FIRST_LEVEL
    }

    /**
     * The lifetime total needed to reach [level] — the inverse of [levelForTotalXp], for a
     * progress bar that has to say how far into the current level the user is.
     *
     * @throws IllegalArgumentException if [level] is below [FIRST_LEVEL], which no user is.
     */
    fun xpRequiredForLevel(level: Int): Long {
        require(level >= FIRST_LEVEL) { "level cannot be below $FIRST_LEVEL, was $level" }
        return XP_PER_LEVEL.toLong() * (level - FIRST_LEVEL)
    }
}
