package com.titan.core.data.mapper

import com.titan.core.data.db.entity.GymEntity
import com.titan.core.domain.backup.model.GymRow
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym

private const val TAG_SEPARATOR = '\n'

/**
 * Packs tags into the single `equipment_tags` column. A tag is trimmed, and blank ones and
 * repeats (ignoring case) are dropped; a tag containing the separator is split in two rather
 * than stored in a form that would read back as something else.
 */
internal fun packTags(tags: List<String>): String =
    tags.flatMap { it.split(TAG_SEPARATOR) }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .joinToString(TAG_SEPARATOR.toString())

internal fun unpackTags(packed: String): List<String> =
    if (packed.isEmpty()) emptyList() else packed.split(TAG_SEPARATOR)

fun Gym.toEntity(): GymEntity =
    GymEntity(
        id = id,
        name = name,
        address = address,
        latitude = coordinates.latitude,
        longitude = coordinates.longitude,
        equipmentTags = packTags(equipmentTags),
        userNotes = userNotes,
        createdAt = createdAt,
    )

fun GymEntity.toDomain(): Gym =
    Gym(
        id = id,
        name = name,
        address = address,
        coordinates = Coordinates(latitude, longitude),
        equipmentTags = unpackTags(equipmentTags),
        userNotes = userNotes,
        createdAt = createdAt,
    )

fun GymEntity.toRow(): GymRow =
    GymRow(
        id = id,
        name = name,
        address = address,
        latitude = latitude,
        longitude = longitude,
        equipmentTags = unpackTags(equipmentTags),
        userNotes = userNotes,
        createdAt = createdAt,
    )

fun GymRow.toEntity(): GymEntity =
    GymEntity(
        id = id,
        name = name,
        address = address,
        latitude = latitude,
        longitude = longitude,
        equipmentTags = packTags(equipmentTags),
        userNotes = userNotes,
        createdAt = createdAt,
    )
