package com.titan.core.domain.model

/**
 * The user's standing against every [AchievementMetric], as of one moment — what the
 * catalogue is evaluated against.
 *
 * A snapshot passed in rather than read from repositories by the evaluator, because the
 * figures are ones the caller has already paid for: when this is built, PR detection has just
 * run and the streak has just been recalculated, so re-reading them would be a second query
 * for an answer already in hand — and a query that could return a *different* answer, if
 * another session landed in between, leaving an achievement unlocked against a state no
 * single moment ever held. Assembling it is the cascade's job (#145), which is the one thing
 * that sees all three figures at once.
 *
 * ### Totals, not deltas
 *
 * Every count is a lifetime figure including the session just finished — 1 after a user's
 * first workout, not 0 — because a threshold is a lifetime threshold. Handing this "records
 * broken *this session*" would make `first_record` unlockable only by the session that broke
 * one, and silently unreachable for a user whose first record predates the achievement.
 *
 * @property sessionsFinished sessions finished over the user's whole history.
 * @property currentStreak the streak held after the session just finished — the figure
 *   [StreakResult.streakAfter] produces, not the one it was computed from.
 * @property recordsBroken personal records broken over the user's whole history, counted per
 *   record rather than per session that broke any, the same way [XpEvent.PrBroken] is emitted.
 */
data class AchievementProgress(
    val sessionsFinished: Int,
    val currentStreak: Int,
    val recordsBroken: Int,
) {

    /**
     * This snapshot's figure for [metric].
     *
     * The one `when` in the achievement machinery, and exhaustive by construction: a metric
     * added later fails to compile here until it is given a figure, rather than defaulting to
     * a zero that would read as "nobody has ever reached this milestone".
     */
    fun valueOf(metric: AchievementMetric): Int =
        when (metric) {
            AchievementMetric.SESSIONS_FINISHED -> sessionsFinished
            AchievementMetric.STREAK_WEEKS -> currentStreak
            AchievementMetric.RECORDS_BROKEN -> recordsBroken
        }
}
