package com.titan.core.domain.gamification

import com.titan.core.domain.model.XpEvent
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

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
 * ### A curve that flattens
 *
 * Reaching level *n* costs `LEVEL_CURVE_BASE × (n − 1)^1.5` lifetime XP, so level 1 is free,
 * level 2 costs 60, level 10 costs 1,620 and level 100 costs 59,102. Each level costs a little
 * more than the one before it, so a new lifter levels up several times a week and a lifter a year
 * in still does about once a week: the reference year reaches level 8 in its first week, 20 in
 * its first month, 40 in its third, and 100 at the end, by when a level takes about three
 * sessions. Year two adds about 58 levels and year three 49 — slower, but still about one a
 * week, and `XpEconomyTest` holds the curve to both ends: one to two levels a week as the first
 * year closes, and at least a level a week across the second.
 *
 * A power rather than an exponential: a cost that grows by a fixed percentage per level can be
 * tuned to reach 100 in a year, but the levels after it then cost a multiple of everything
 * before, and a lifter in year two who trains just as hard would watch the bar stop. The
 * exponent is what trades the two ends off — a bigger one makes the first weeks faster and the
 * second year slower — and 1.5 is the largest that keeps the second year above a level a week.
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

    /**
     * What level 2 costs, and the scale of the whole curve: level *n* costs this times
     * `(n − 1)^1.5` — see the class doc.
     */
    const val LEVEL_CURVE_BASE = 60

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
     * The level a lifetime total of [totalXp] points buys: the highest level whose
     * [xpRequiredForLevel] is at most [totalXp].
     *
     * [FIRST_LEVEL] at zero, and a total sitting exactly on a threshold is already at the level
     * it pays for: 60 points is level 2, 169 is still level 2, 170 is level 3.
     *
     * Inverts the curve in floating point for a first guess and then settles it against
     * [xpRequiredForLevel] itself, so the two can never disagree about a boundary over a
     * rounding error — the level is defined by the thresholds, not by the inverse.
     *
     * @throws IllegalArgumentException if [totalXp] is negative. XP is only ever added —
     *   nothing in the app spends it — so a negative total is a corrupt row rather than a
     *   state to render a level for.
     */
    fun levelForTotalXp(totalXp: Long): Int {
        require(totalXp >= 0) { "totalXp cannot be negative, was $totalXp" }
        var level = FIRST_LEVEL + (totalXp.toDouble() / LEVEL_CURVE_BASE).pow(1 / LEVEL_CURVE_EXPONENT).toInt()
        while (level > FIRST_LEVEL && xpRequiredForLevel(level) > totalXp) level--
        while (xpRequiredForLevel(level + 1) <= totalXp) level++
        return level
    }

    /**
     * The lifetime total needed to reach [level] — the inverse of [levelForTotalXp], for a
     * progress bar that has to say how far into the current level the user is.
     *
     * Rounded to the nearest point, which keeps every threshold a whole number of points and
     * every level dearer than the one before it.
     *
     * @throws IllegalArgumentException if [level] is below [FIRST_LEVEL], which no user is.
     */
    fun xpRequiredForLevel(level: Int): Long {
        require(level >= FIRST_LEVEL) { "level cannot be below $FIRST_LEVEL, was $level" }
        val steps = (level - FIRST_LEVEL).toDouble()
        return (LEVEL_CURVE_BASE * steps * sqrt(steps)).roundToLong()
    }

    /**
     * What [level] costs on its own — the points between reaching it and reaching the next one,
     * which is the width of the bar drawn while the user stands on it.
     *
     * @throws IllegalArgumentException if [level] is below [FIRST_LEVEL].
     */
    fun xpToNextLevel(level: Int): Int = (xpRequiredForLevel(level + 1) - xpRequiredForLevel(level)).toInt()

    /** The `1.5` of the curve, for the inverse; [xpRequiredForLevel] spells it as `n·√n`. */
    private const val LEVEL_CURVE_EXPONENT = 1.5
}
