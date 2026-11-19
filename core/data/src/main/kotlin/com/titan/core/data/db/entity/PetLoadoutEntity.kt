package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One piece of gear worn by one pet. The key `(pet_id, slot)` is the whole model: a pet wears one
 * piece per slot, and wearing another replaces it. Gear is cosmetic and is not a drop, so the row
 * names the gear's key rather than a `collected_item`. Neither key is validated, for
 * `species_id`'s reason. Cascades with its pet.
 */
@Entity(
    tableName = "pet_loadout",
    primaryKeys = ["pet_id", "slot"],
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["pet_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PetLoadoutEntity(
    @ColumnInfo(name = "pet_id")
    val petId: Long,
    @ColumnInfo(name = "slot")
    val slot: String,
    @ColumnInfo(name = "gear")
    val gear: String,
)
