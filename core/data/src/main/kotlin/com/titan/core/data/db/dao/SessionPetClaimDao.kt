package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.titan.core.data.db.entity.SessionPetClaimEntity

/** The pet-growth fence. [SessionLootClaimDao]'s shape, over another table. */
@Dao
interface SessionPetClaimDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(claim: SessionPetClaimEntity): Long

    /** True the first time a session is claimed, false every time after. */
    suspend fun claim(sessionId: Long): Boolean = insertOrIgnore(SessionPetClaimEntity(sessionId)) != NOT_INSERTED
}
