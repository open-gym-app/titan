package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One food drop eaten.
 *
 * The drop row is kept, as an egg's is: the drop happened. [collectedItemId] is the key, so an
 * item is eaten once and the database refuses the second. `RESTRICT` on the drop for `pet`'s
 * reason; `SET NULL` on the pet, because what was eaten stays eaten.
 */
@Entity(
    tableName = "pet_feed",
    foreignKeys = [
        ForeignKey(
            entity = CollectedItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["collected_item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["pet_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("pet_id")],
)
data class PetFeedEntity(
    @PrimaryKey
    @ColumnInfo(name = "collected_item_id")
    val collectedItemId: Long,
    @ColumnInfo(name = "pet_id")
    val petId: Long?,
    /** Epoch millis, UTC. */
    @ColumnInfo(name = "fed_at")
    val fedAt: Long,
)

/**
 * The newest drop of a food the user has looked at. A food reads as new while it holds a drop
 * newer than [seenUpTo], so "new" clears once seen and comes back only with a later drop.
 */
@Entity(tableName = "food_seen")
data class FoodSeenEntity(
    @PrimaryKey
    @ColumnInfo(name = "collectible_id")
    val collectibleId: String,
    @ColumnInfo(name = "seen_up_to")
    val seenUpTo: Long,
)
