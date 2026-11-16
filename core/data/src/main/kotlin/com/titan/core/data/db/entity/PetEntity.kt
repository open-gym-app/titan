package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One hatched pet — a *titan*, on screen.
 *
 * ### The egg row is kept, and is what makes an egg hatch once
 *
 * `collected_item` records what dropped, and the drop happened, so hatching does not consume the
 * row: it points at it. The **unique** index on [hatchedFromItemId] is the fence — the database
 * refuses a second pet from the same egg, so nothing above it needs one of its own.
 *
 * `RESTRICT` rather than `CASCADE`: nothing deletes a drop row today, and if something ever does
 * it must not take a pet with it silently.
 *
 * ### `species_id` is not validated
 *
 * An id this build does not know is a pet from a newer version and must survive a restore, the
 * rule `achievement` and `collected_item` already follow.
 *
 * ### At most one active pet, held above the table
 *
 * Room's `@Index` cannot express a partial unique index, so "one row with `is_active = 1`" is
 * kept by `PetDao.makeActive`'s transaction and every read degrades to the lowest flagged id.
 */
@Entity(
    tableName = "pet",
    foreignKeys = [
        ForeignKey(
            entity = CollectedItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["hatched_from_item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["hatched_from_item_id"], unique = true)],
)
data class PetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "species_id")
    val speciesId: String,
    @ColumnInfo(name = "hatched_from_item_id")
    val hatchedFromItemId: Long,
    /** Epoch millis, UTC. */
    @ColumnInfo(name = "hatched_at")
    val hatchedAt: Long,
    @ColumnInfo(name = "xp")
    val xp: Long = 0,
    /** Base stats, before anything worn is counted. */
    @ColumnInfo(name = "strength")
    val strength: Int = 0,
    @ColumnInfo(name = "endurance")
    val endurance: Int = 0,
    @ColumnInfo(name = "mobility")
    val mobility: Int = 0,
    @ColumnInfo(name = "nickname")
    val nickname: String? = null,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false,
)
