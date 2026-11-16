package com.titan.core.data.mapper

import com.titan.core.data.db.entity.GymEntity
import com.titan.core.domain.backup.model.GymRow

fun GymEntity.toRow(): GymRow =
    GymRow(
        id = id,
        name = name,
        address = address,
        latitudeMicro = latitudeMicro,
        longitudeMicro = longitudeMicro,
        equipmentTags = equipmentTags,
        userNotes = userNotes,
        createdAt = createdAt,
    )

fun GymRow.toEntity(): GymEntity =
    GymEntity(
        id = id,
        name = name,
        address = address,
        latitudeMicro = latitudeMicro,
        longitudeMicro = longitudeMicro,
        equipmentTags = equipmentTags,
        userNotes = userNotes,
        createdAt = createdAt,
    )
