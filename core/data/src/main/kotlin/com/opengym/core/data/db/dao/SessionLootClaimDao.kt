package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.opengym.core.data.db.entity.SessionLootClaimEntity

/**
 * The per-session loot claims — see [SessionLootClaimEntity] for what they fence off.
 *
 * [SessionXpClaimDao]'s twin, and write-only for its reason: nothing needs to *ask* whether a
 * session was rolled for, because the only question anybody has is "may I roll this one", and
 * taking the claim is what answers it.
 */
@Dao
interface SessionLootClaimDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(claim: SessionLootClaimEntity): Long

    /**
     * Claims [sessionId]'s roll, and answers whether **this call** claimed it: true only when
     * no claim was on file, false for every call after the first.
     *
     * One `INSERT OR IGNORE`, for the reason [SessionXpClaimDao.claim] is one: the check and
     * the write must not be interleavable, or two cascades for the same session could both be
     * told they may roll.
     */
    suspend fun claim(sessionId: Long): Boolean = insertOrIgnore(SessionLootClaimEntity(sessionId)) != NOT_INSERTED
}
