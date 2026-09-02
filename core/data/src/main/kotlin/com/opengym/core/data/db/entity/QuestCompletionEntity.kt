package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One quest the user has completed, in one period.
 *
 * A row exists only for a quest actually finished. The catalogue is data in `:core:domain`
 * ([com.opengym.core.domain.gamification.Quests]) rather than a seeded table, the same call
 * [AchievementEntity] makes, so adding a quest is a line there and no schema change at all.
 *
 * ### Only the completions, never the progress
 *
 * There is no counter here. A quest's progress is the same sessions, records and volume the
 * progression screens already aggregate, and a second copy of those figures would be one that
 * could drift from the first — see `QuestProgressUseCase`, which derives progress instead of
 * storing it. What cannot be re-derived is which quests were *banked*, because that is what XP
 * was paid on and the figures behind it can change afterwards: a user who deletes a session
 * they were already paid for keeps the quest.
 *
 * ### The primary key is the pair
 *
 * `(quest_id, period_start)`, not a surrogate rowid, for the reason [AchievementEntity]'s id is
 * its own key: the uniqueness *is* the contract — completing one quest twice in one period must
 * leave one row, since that answer is what the caller pays XP on — and making the natural key
 * the primary key is what lets `QuestCompletionDao.insertIfAbsent` be a single `INSERT OR
 * IGNORE` rather than a read followed by a write two cascades could interleave.
 *
 * The pair rather than the id alone is the whole difference between a quest and an achievement.
 * An achievement is held forever; a quest recurs, so the *period* is half of what identifies a
 * completion.
 *
 * @property questId the stable key from [com.opengym.core.domain.model.Quest.id]. Part of the
 *   storage contract: renaming one orphans every row written under the old spelling, and the
 *   quest becomes completable again in a period it was already paid for — which is why the
 *   domain pins the ids with a test.
 * @property periodStart the **epoch day** of the period's first day, in UTC, matching the
 *   `week_start` the weekly aggregates are keyed by. A day rather than a millisecond timestamp
 *   because a period boundary is a date, and storing it as an instant would invite two rows
 *   that mean the same period and differ by the time of day.
 * @property completedAt when the quest was **earned** — the finishing session's own timestamp,
 *   in epoch milliseconds, not the moment the row was written, so a cascade retried an hour
 *   later does not date the completion to the retry.
 */
@Entity(tableName = "quest_completion", primaryKeys = ["quest_id", "period_start"])
data class QuestCompletionEntity(
    @ColumnInfo(name = "quest_id")
    val questId: String,
    @ColumnInfo(name = "period_start")
    val periodStart: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long,
)
