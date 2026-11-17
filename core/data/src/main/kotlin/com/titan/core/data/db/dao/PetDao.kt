package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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

    @Query("SELECT * FROM pet WHERE hatched_from_item_id = :collectedItemId")
    suspend fun findByEgg(collectedItemId: Long): PetEntity?

    /**
     * Inserts a newly hatched pet, active if it is the first.
     *
     * One transaction, so two first hatches cannot both see an empty table and both come out
     * active.
     */
    @Transaction
    suspend fun hatch(pet: PetEntity): Long = insert(pet.copy(isActive = count() == 0))

    /** The lowest flagged id, or null with no active pet. */
    @Query("SELECT MIN(id) FROM pet WHERE is_active = 1")
    suspend fun activeId(): Long?

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

    @Query("SELECT * FROM pet WHERE is_active = 1 ORDER BY id LIMIT 1")
    suspend fun active(): PetEntity?

    @Update
    suspend fun update(pet: PetEntity)

    /**
     * Rewrites the active pet through [change], in one transaction, and answers with the row
     * before and after — or null, having written nothing, with no active pet.
     *
     * A read-modify-write rather than an `UPDATE ... SET xp = xp + ?`: the caller clamps, and
     * needs both rows to report what the write did without reading again.
     */
    @Transaction
    suspend fun changeActive(change: (PetEntity) -> PetEntity): Pair<PetEntity, PetEntity>? {
        val before = active() ?: return null
        val after = change(before)
        update(after)
        return before to after
    }
}
