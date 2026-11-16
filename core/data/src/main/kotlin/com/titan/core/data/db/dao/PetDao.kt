package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.titan.core.data.db.entity.PetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PetDao {

    /** Throws `SQLiteConstraintException` for a second pet from the same egg. */
    @Insert
    suspend fun insert(pet: PetEntity): Long

    /** Flagged rows first, then oldest row first. The caller decides which flagged row counts. */
    @Query("SELECT * FROM pet ORDER BY is_active DESC, id")
    fun observeAll(): Flow<List<PetEntity>>

    /** The lowest flagged id: the degrade for a table holding two. */
    @Query("SELECT * FROM pet WHERE is_active = 1 ORDER BY id LIMIT 1")
    fun observeActive(): Flow<PetEntity?>

    @Query("SELECT COUNT(*) FROM pet")
    suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM pet WHERE id = :id)")
    suspend fun exists(id: Long): Boolean

    @Query("UPDATE pet SET is_active = (id = :id)")
    suspend fun flagOnly(id: Long)

    /**
     * Makes [id] the one active pet, or changes nothing if there is no such pet.
     *
     * One transaction, so no reader sees zero active pets between the clear and the set, and a
     * missing id does not deactivate the pet that was active.
     */
    @Transaction
    suspend fun makeActive(id: Long): Boolean {
        if (!exists(id)) return false
        flagOnly(id)
        return true
    }

    /** @return how many rows changed: 1, or 0 for an id that is not there. */
    @Query("UPDATE pet SET nickname = :nickname WHERE id = :id")
    suspend fun rename(
        id: Long,
        nickname: String?,
    ): Int
}
