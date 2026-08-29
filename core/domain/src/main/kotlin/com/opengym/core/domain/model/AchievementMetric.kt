package com.opengym.core.domain.model

/**
 * What an [Achievement] is measured in.
 *
 * Deliberately few, and deliberately all *counters*: every milestone in the catalogue is
 * "reach N of something", which is what lets one comparison ([Achievement.isUnlockedBy])
 * evaluate the whole set. A condition that is not a counter — "train on a Sunday", "log a
 * session in every discipline" — does not belong here; it belongs to the quest mechanic
 * (#105), which is built for conditions rather than thresholds.
 *
 * Not persisted: what is written to `achievement.achievement_id` is [Achievement.id], not
 * this, so a constant here can be renamed without a migration. Adding one costs a branch in
 * [AchievementProgress.valueOf] and nothing else.
 */
enum class AchievementMetric {

    /** Sessions the user has finished, over their whole history. */
    SESSIONS_FINISHED,

    /** The streak the user holds right now — see [StreakResult]. */
    STREAK_DAYS,

    /** Personal records broken, over the user's whole history. */
    RECORDS_BROKEN,
}
