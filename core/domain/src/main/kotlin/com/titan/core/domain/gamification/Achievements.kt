package com.titan.core.domain.gamification

import com.titan.core.domain.model.Achievement
import com.titan.core.domain.model.AchievementMetric

/**
 * The achievements this build ships, and the only place their thresholds are written down.
 *
 * A list of values, not a chain of conditions — see [Achievement] for why the shape is the
 * decision here. Everything that evaluates, unlocks or displays an achievement reads [ALL],
 * so a milestone added below needs no other edit anywhere: no branch, no XP wiring (#33
 * prices every unlock the same), no new idempotency argument.
 *
 * ### The initial six
 *
 * Two ladders and two firsts. The session ladder (1 / 10 / 100) rewards showing up at all,
 * then persisting, then persisting for a year or two; the streak ladder (7 / 30) rewards
 * consistency, which the sessions count cannot see — a hundred sessions in bursts and a
 * hundred sessions a week apart are the same number and not the same habit. `first_record`
 * sits apart from both: it is the first time the *training* went somewhere, rather than the
 * first time the app was opened.
 *
 * **These values are starting defaults**, like #33's XP constants and
 * [StreakCalculator.STREAK_GRACE_HOURS], and for the same reason — nothing has been validated
 * against real usage yet. Each is named below and read from nowhere else, so retuning one is
 * an edit to a single line, and a test that wants "one short of the threshold" asks the
 * catalogue for the threshold rather than restating the number.
 *
 * ### The ids are a storage contract
 *
 * [Achievement.id] is what `AchievementEntity.achievementId` holds (#35) and what
 * [com.titan.core.domain.model.XpEvent.AchievementUnlocked] carries. Renaming one does not
 * rename the rows already written under the old spelling: the user's unlock stops matching
 * any catalogue entry, and the achievement re-unlocks and re-awards its XP on their next
 * session. `AchievementsTest` pins the id set for exactly that reason — a failure there is
 * the reminder that a rename needs a migration, not a bug in the test.
 */
object Achievements {

    /** Finish a first workout. The one everybody unlocks, on the session that starts it all. */
    val FIRST_SESSION = Achievement(
        id = "first_session",
        metric = AchievementMetric.SESSIONS_FINISHED,
        threshold = 1,
    )

    /** Ten sessions in: past the point where a training app is a thing being tried out. */
    val TEN_SESSIONS = Achievement(
        id = "ten_sessions",
        metric = AchievementMetric.SESSIONS_FINISHED,
        threshold = 10,
    )

    /** A hundred sessions: a year or two of training, on most people's schedule. */
    val HUNDRED_SESSIONS = Achievement(
        id = "hundred_sessions",
        metric = AchievementMetric.SESSIONS_FINISHED,
        threshold = 100,
    )

    /** A week's streak — seven sessions without letting the grace period lapse. */
    val SEVEN_DAY_STREAK = Achievement(
        id = "seven_day_streak",
        metric = AchievementMetric.STREAK_DAYS,
        threshold = 7,
    )

    /** A month's streak, on the same rolling window — see [StreakCalculator]. */
    val THIRTY_DAY_STREAK = Achievement(
        id = "thirty_day_streak",
        metric = AchievementMetric.STREAK_DAYS,
        threshold = 30,
    )

    /** Break a first personal record, of any [com.titan.core.domain.model.PrType]. */
    val FIRST_RECORD = Achievement(
        id = "first_record",
        metric = AchievementMetric.RECORDS_BROKEN,
        threshold = 1,
    )

    /**
     * Every achievement this build knows about, in the order unlocks are evaluated and
     * therefore reported — cheapest milestone of a metric first, so a user who clears two
     * rungs of the same ladder at once reads as having earned the smaller one first.
     */
    val ALL: List<Achievement> = listOf(
        FIRST_SESSION,
        TEN_SESSIONS,
        HUNDRED_SESSIONS,
        SEVEN_DAY_STREAK,
        THIRTY_DAY_STREAK,
        FIRST_RECORD,
    )
}
