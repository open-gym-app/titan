package com.titan.core.domain.gamification

import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.XpEvent
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * What each [XpEvent] is worth, and what a lifetime total means as a level.
 *
 * Pure arithmetic over named constants — no repository, no clock — so the numbers below are
 * the only thing anybody has to touch to retune the economy, and a test can pin the curve
 * without a database. [com.titan.core.domain.usecase.AwardXpUseCase] turns these answers into a
 * stored total; [SessionXp] decides *which* events a finished session earns.
 *
 * ### The sources
 *
 * There are five ways to earn XP, and this is the whole list:
 *
 * | Source | Worth | Bounded by |
 * |---|---|---|
 * | A working set ([XpEvent.SetLogged]) | [XP_PER_SET] | [SessionXp.MAX_PAID_SETS] a session, no warm-ups |
 * | A session finished ([XpEvent.SessionFinished]) | [XP_PER_SESSION] | [SessionXp.MIN_SETS_FOR_SESSION_XP]+ sets |
 * | A record beaten ([XpEvent.PrBroken]) | [xpForRecord], 10 to 20 | [SessionXp.MAX_PAID_RECORDS] a session, no baselines |
 * | A milestone unlocked ([XpEvent.AchievementUnlocked]) | [xpForAchievement], 50 to 250 | once per achievement, ever |
 * | A quest completed ([XpEvent.QuestCompleted]) | its own price, in `Quests` | once per quest per period |
 *
 * The shape is deliberate. The drip (sets, sessions) is small and capped per session, so grinding
 * is worth less the more of it there is and a session cannot be inflated by padding it. The
 * lumps (records, milestones, quests) are the part that rewards *progress*, and each one can only
 * be earned by getting better or staying consistent — none of them can be repeated for free.
 *
 * ### A power curve
 *
 * Reaching level *n* costs `100 × (n − 1)^1.5` lifetime XP, so each level costs more than the
 * last: level 2 is 100, level 3 is 283, level 5 is 800, level 10 is 2,700, level 20 is about
 * 8,300 and level 50 about 34,300. A lifter training three times a week earns roughly 250 XP a
 * week with the quests, which puts the ranks (see [LevelTitles]) at about a week for Regular,
 * a month for Iron Disciple, three months for Steel Forged and two to three years for Legend.
 *
 * Steeper than linear, gentler than the exponential curves games use: early levels come fast
 * enough to make the first weeks feel like progress, and the top is far enough off that a rank
 * means something, without a level ever costing so much that a week of training stops moving
 * the bar.
 *
 * ### The values are starting defaults
 *
 * Nothing here is validated against real usage. Everything is named and read from nowhere else,
 * so tuning is an edit in this file, [SessionXp] and `Quests`, and the relationships between
 * them are pinned by tests rather than by this prose.
 */
object XpCalculator {

    /** Points for one working set — see [XpEvent.SetLogged]. */
    const val XP_PER_SET = 2

    /** Points for finishing a session that did real work — see [XpEvent.SessionFinished]. */
    const val XP_PER_SESSION = 15

    /** Points for a beaten weight or estimated max — the headline records of a strength session. */
    const val XP_PER_HEADLINE_RECORD = 20

    /** Points for a beaten distance, pace or loaded hold — the headline records of the other disciplines. */
    const val XP_PER_ENDURANCE_RECORD = 15

    /** Points for a beaten rep, volume or duration figure — the ones that move when a headline one does. */
    const val XP_PER_SECONDARY_RECORD = 10

    /** Points for a bronze achievement — the bottom rung of a ladder. */
    const val XP_PER_BRONZE_ACHIEVEMENT = 50

    /** Points for a silver achievement — a rung between the bottom and the top. */
    const val XP_PER_SILVER_ACHIEVEMENT = 100

    /** Points for a gold achievement — the top rung of a ladder. */
    const val XP_PER_GOLD_ACHIEVEMENT = 250

    /** Lifetime XP that buys level 2, and the scale the rest of the curve is a multiple of. */
    const val LEVEL_CURVE_SCALE = 100.0

    /** The level a user starts at, holding no XP at all. */
    const val FIRST_LEVEL = 1

    /**
     * The highest level [levelForTotalXp] reports. Not a gameplay cap — the level costs about
     * three million XP, which is out of reach — but what keeps a corrupt or imported total from
     * asking for a level that does not fit an `Int`.
     */
    const val MAX_LEVEL = 1_000

    /**
     * What [event] is worth.
     *
     * Exhaustive over [XpEvent] by construction: a source added later (a daily challenge)
     * fails to compile here until it is priced, which is the intended failure mode — the
     * alternative, an `else` branch, would quietly award a new source nothing at all and look
     * like a tuning value of zero rather than an oversight.
     */
    fun xpFor(event: XpEvent): Int =
        when (event) {
            XpEvent.SetLogged -> XP_PER_SET

            XpEvent.SessionFinished -> XP_PER_SESSION

            is XpEvent.PrBroken -> xpForRecord(event.prType)

            is XpEvent.AchievementUnlocked -> xpForAchievement(event.tier)

            // The only variant that prices itself. A quest's reward is part of its definition
            // rather than a property of the kind, so it arrives on the event; see
            // [XpEvent.QuestCompleted], which carries the argument.
            is XpEvent.QuestCompleted -> event.xp
        }

    /**
     * What beating a [type] record is worth.
     *
     * Priced by how hard the figure is to move: a heavier bar or a better estimated max is the
     * headline of a strength session (20), a distance, pace or loaded hold is the same for the
     * other disciplines (15), and the figures that move almost by accident when the headline one
     * does — more reps, more volume, a longer hold — are worth half-ish (10). A session that
     * beats a lift's weight, estimated max and volume together pays 40, not 60 for one achievement.
     */
    fun xpForRecord(type: PrType): Int =
        when (type) {
            PrType.MAX_WEIGHT, PrType.ESTIMATED_1RM -> XP_PER_HEADLINE_RECORD
            PrType.MAX_DISTANCE, PrType.BEST_PACE, PrType.MAX_WEIGHTED_HOLD -> XP_PER_ENDURANCE_RECORD
            PrType.MAX_REPS, PrType.MAX_VOLUME, PrType.MAX_DURATION -> XP_PER_SECONDARY_RECORD
        }

    /** What unlocking an achievement of [tier] is worth — the harder the rung, the more. */
    fun xpForAchievement(tier: AchievementTier): Int =
        when (tier) {
            AchievementTier.BRONZE -> XP_PER_BRONZE_ACHIEVEMENT
            AchievementTier.SILVER -> XP_PER_SILVER_ACHIEVEMENT
            AchievementTier.GOLD -> XP_PER_GOLD_ACHIEVEMENT
        }

    /**
     * The level a lifetime total of [totalXp] points buys.
     *
     * [FIRST_LEVEL] at zero, and a total sitting exactly on a threshold is already at the level
     * it pays for: 100 points is level 2, 282 is still level 2, 283 is level 3.
     *
     * @throws IllegalArgumentException if [totalXp] is negative. XP is only ever added —
     *   nothing in the app spends it — so a negative total is a corrupt row rather than a
     *   state to render a level for.
     */
    fun levelForTotalXp(totalXp: Long): Int {
        require(totalXp >= 0) { "totalXp cannot be negative, was $totalXp" }

        // The closed-form inverse of the curve lands on or next to the answer; the two loops
        // settle it against the curve itself, so the rounding the curve does can never disagree
        // with the level this reports.
        val estimate = (totalXp / LEVEL_CURVE_SCALE).pow(2.0 / 3.0).toInt() + FIRST_LEVEL
        var level = estimate.coerceIn(FIRST_LEVEL, MAX_LEVEL)
        while (level > FIRST_LEVEL && xpRequiredForLevel(level) > totalXp) level--
        while (level < MAX_LEVEL && xpRequiredForLevel(level + 1) <= totalXp) level++
        return level
    }

    /**
     * The lifetime total needed to reach [level] — the inverse of [levelForTotalXp], for a
     * progress bar that has to say how far into the current level the user is.
     *
     * @throws IllegalArgumentException if [level] is below [FIRST_LEVEL], which no user is.
     */
    fun xpRequiredForLevel(level: Int): Long {
        require(level >= FIRST_LEVEL) { "level cannot be below $FIRST_LEVEL, was $level" }
        val steps = (level - FIRST_LEVEL).toDouble()
        return (LEVEL_CURVE_SCALE * steps * sqrt(steps)).roundToLong()
    }

    /**
     * What it costs to climb from [level] to the next one — the width of that level's bar.
     *
     * @throws IllegalArgumentException if [level] is below [FIRST_LEVEL].
     */
    fun xpToNextLevel(level: Int): Int = (xpRequiredForLevel(level + 1) - xpRequiredForLevel(level)).toInt()
}
