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
 * ### The values are tuned to a year
 *
 * The target is **level 100 after a year** of training to the default weekly goal — four
 * sessions a week, every week — and `XpEconomyTest` pins it: a reference year built from these
 * constants, the quest catalogue and the achievements a year reaches has to land there. Nothing
 * reads a literal instead of these, so tuning is an edit here and nowhere else, and a retune that
 * moves the year fails that test rather than shipping.
 *
 * The relationships are the part worth arguing about. Showing up is what pays: a set is the
 * small, frequent drip, and a session is worth ten of them. A record is worth two sets — a bonus
 * on top of the work rather than the bulk of it, because records are not evenly spread. A
 * lifter's first sessions set a record for every exercise and every kind at once, and one set
 * that is heavier for more reps beats four records in a single stroke; priced higher, records
 * would make the level a measure of how new somebody is to the app rather than how much they
 * have trained. An achievement, once in an install's life, is worth two sessions.
 *
 * This prices an event; whether a session earns it at all is [SessionXp]'s call. Warm-ups, empty
 * ticks and a first-ever figure for an exercise earn nothing there, and a session's sets and
 * records are capped, which is what keeps these prices from being farmed.
 *
 * ### A linear curve
 *
 * Level *n* costs the same additional XP as the level before it — `XP_PER_LEVEL × (n − 1)`
 * to reach level *n* at all, so level 1 is free, level 2 costs 600 and level 12 costs 6,600.
 * Not the exponential curve most XP systems use: this one is a training log rather than a
 * game with an endgame to pace people towards, and a curve that stretches means a lifter
 * who trains just as hard in year two sees their bar move slower for it. Linear keeps "a
 * week at the goal is about two levels" — the year's target, 99 levels over 52 weeks — true
 * forever.
 */
object XpCalculator {

    /** Points for one logged set — see [XpEvent.SetLogged]. */
    const val XP_PER_SET = 5

    /** Points for finishing a session — see [XpEvent.SessionFinished]. */
    const val XP_PER_SESSION = 50

    /** Points for one broken personal record — see [XpEvent.PrBroken]. */
    const val XP_PER_PR = 10

    /** Points for one unlocked achievement — see [XpEvent.AchievementUnlocked]. */
    const val XP_PER_ACHIEVEMENT = 100

    /** The cost of every level, the whole of the curve — see the class doc. */
    const val XP_PER_LEVEL = 600

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
        }

    /**
     * The level a lifetime total of [totalXp] points buys.
     *
     * [FIRST_LEVEL] at zero, one level up per [XP_PER_LEVEL] points thereafter, and a total
     * sitting exactly on a threshold is already at the level it pays for: 600 points is
     * level 2, 1,199 is still level 2, 1,200 is level 3.
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
