package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * A marker saying that one session's drop table has been rolled.
 *
 * [SessionXpClaimEntity]'s twin, column for column, and for the twin of its reason. The
 * post-session cascade has two steps that a re-entry could repeat: banking XP, because `addXp`
 * banks whatever it is handed, and rolling loot, because a roll is a decision taken from a
 * random source. A row here is what stops a re-entered cascade handing out a second collectible
 * for one workout.
 *
 * ### Why the fence cannot live on `collected_item`
 *
 * Everywhere else in the cascade the write itself is the fence — an unlock and a quest
 * completion are both `INSERT OR IGNORE` over a natural key, so the row that already exists is
 * the answer. The inventory cannot do that: the same collectible dropping **again** is a normal
 * thing to hold, so a unique index over it would refuse the second legitimate drop as readily as
 * the duplicate one. See [CollectedItemEntity], which says the same thing from the other side.
 * So the fence goes over the *roll*, one table up, where the thing being made unrepeatable is
 * the decision rather than its result.
 *
 * One column and no timestamp, exactly as the XP claim has none: the claim is the row's
 * existence, and the session it belongs to already carries when it finished.
 *
 * A claim of its own rather than a share of `session_xp_claim`, because the two fence
 * independent steps — a run that died after the awards and before the roll must still be able
 * to roll on re-entry, which one row saying "the cascade ran" could not express.
 *
 * Cascades with its session, for [SessionXpClaimEntity]'s reason: a claim outliving the workout
 * it fences would protect a session that no longer exists, and rowids are never reused
 * (`workout_session.id` is `AUTOINCREMENT`), so nothing can inherit a stale one.
 */
@Entity(
    tableName = "session_loot_claim",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionLootClaimEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
)
