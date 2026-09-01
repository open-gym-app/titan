package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * A marker saying that one session's XP has been paid for.
 *
 * The fence behind [com.opengym.core.domain.repository.GamificationRepository.claimSessionXp].
 * Every other step of the post-session cascade is idempotent by construction — PR detection
 * compares strictly better, an already-counted day leaves the streak unchanged, an unlock is
 * insert-if-absent — and XP is the one that is not, because `addXp` banks what it is handed.
 * A row here is what stops a re-entered cascade paying for the same workout twice.
 *
 * One column and no timestamp: the claim is the row's existence, and the method that takes it
 * is handed no moment to record. The session it belongs to already carries when it finished.
 *
 * Cascades with its session. A claim outliving the workout it fences would protect a session
 * that no longer exists, and rowids are never reused (`workout_session.id` is
 * `AUTOINCREMENT`), so nothing can inherit a stale one.
 */
@Entity(
    tableName = "session_xp_claim",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionXpClaimEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
)
