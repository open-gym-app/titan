package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.opengym.core.data.db.entity.SessionXpClaimEntity

/**
 * The per-session XP claims — see [SessionXpClaimEntity] for what they fence off.
 *
 * Write-only, and that is the whole API: nothing needs to *ask* whether a session was
 * claimed, because the only question anybody has is "may I pay for this one", and taking
 * the claim is what answers it.
 */
@Dao
interface SessionXpClaimDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(claim: SessionXpClaimEntity): Long

    /**
     * Claims [sessionId]'s XP, and answers whether **this call** claimed it: true only when
     * no claim was on file, false for every call after the first.
     *
     * One `INSERT OR IGNORE`, for the reason [AchievementDao.insertIfAbsent] is one: the
     * check and the write must not be interleavable, or two cascades for the same session
     * could both be told they may pay.
     */
    suspend fun claim(sessionId: Long): Boolean = insertOrIgnore(SessionXpClaimEntity(sessionId)) != NOT_INSERTED
}
