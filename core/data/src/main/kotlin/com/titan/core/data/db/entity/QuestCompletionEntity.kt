package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One quest the user has completed, in one period.
 *
 * A row exists only for a quest actually finished. The catalogue is data in `:core:domain`
 * ([com.titan.core.domain.gamification.Quests]) rather than a seeded table, the same call
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
 * ### And an index on the period, beside that key
 *
 * Nothing ever asks for one quest's completion. Both reads — the board's and the completion
 * pass's — ask which quests were completed *in a period*, so they filter on `period_start`
 * alone. A composite index is only usable from its leading column, and this one leads with
 * `quest_id`, so without the index below every such read is a full table scan.
 *
 * Declaring the key as `(period_start, quest_id)` instead would fix it for free — the implicit
 * index would then lead with the column both reads filter on, and `INSERT OR IGNORE` does not
 * care which way round the pair is written. It is spelled out as its own index rather than left
 * to that, because a key's column order is the kind of thing a later reader reorders for
 * legibility, and the reads would go back to scanning with nothing to say they had.
 *
 * The cost of being explicit is a second B-tree over a table that grows by at most one row per
 * quest per period — a few hundred rows a year — which is the smaller of the two prices.
 *
 * @property questId the stable key from [com.titan.core.domain.model.Quest.id]. Part of the
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
@Entity(
    tableName = "quest_completion",
    primaryKeys = ["quest_id", "period_start"],
    indices = [Index("period_start")],
)
data class QuestCompletionEntity(
    @ColumnInfo(name = "quest_id")
    val questId: String,
    @ColumnInfo(name = "period_start")
    val periodStart: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long,
)
