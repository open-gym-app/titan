package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A pet's level and power as of one UTC week, filed by the growth step inside its own write and
 * replaced by later growth in the same week. What a past self is rebuilt from, exactly, whatever
 * the power formula becomes. Cascades with its pet.
 */
@Entity(
    tableName = "pet_power_snapshot",
    primaryKeys = ["pet_id", "week_start"],
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["pet_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PetPowerSnapshotEntity(
    @ColumnInfo(name = "pet_id")
    val petId: Long,
    /** The week's Monday, 00:00 UTC, in epoch millis. */
    @ColumnInfo(name = "week_start")
    val weekStart: Long,
    @ColumnInfo(name = "level")
    val level: Int,
    @ColumnInfo(name = "power")
    val power: Int,
)

/**
 * One bout against a past self: what it was fought at, and how it went. Feeds the rival's
 * "beaten" mark and the spars-won count the dojo and the scarf unlock on. Cascades with its pet.
 */
@Entity(
    tableName = "pet_spar",
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["pet_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pet_id")],
)
data class PetSparEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "pet_id")
    val petId: Long,
    /** The rival's stable key (`SparRivalKind.key`). */
    @ColumnInfo(name = "rival")
    val rival: String,
    @ColumnInfo(name = "rival_week")
    val rivalWeek: Long,
    @ColumnInfo(name = "power")
    val power: Int,
    @ColumnInfo(name = "rival_power")
    val rivalPower: Int,
    @ColumnInfo(name = "won")
    val won: Boolean,
    @ColumnInfo(name = "rounds_won")
    val roundsWon: Int,
    @ColumnInfo(name = "rounds_lost")
    val roundsLost: Int,
    /** Epoch millis, UTC. */
    @ColumnInfo(name = "fought_at")
    val foughtAt: Long,
)
