package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The marker that a session's pet growth has been paid — [SessionLootClaimEntity]'s shape, one
 * column and no payload, over the third un-idempotent step of the post-session cascade.
 *
 * Growth is an increment to the active pet, so a re-entered cascade would add it again. The row
 * is written **before** the growth and whether or not there is an active pet to grow: a claim
 * that only closed on a payout would let a first pet, hatched later, be billed for every session
 * finished before it existed.
 *
 * Cascades with its session, like the other two claims.
 */
@Entity(
    tableName = "session_pet_claim",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionPetClaimEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
)
