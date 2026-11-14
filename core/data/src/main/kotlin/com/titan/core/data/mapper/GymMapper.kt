package com.titan.core.data.mapper

import com.titan.core.data.db.entity.GymEntity
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * [GymEntity] to and from [Gym].
 *
 * Two columns are encoded rather than copied: coordinates cross as integer microdegrees
 * (rounded to the nearest, ~11 cm), and the tag list as one tag per line.
 */
class GymMapper @Inject constructor() {

    fun toDomain(entity: GymEntity): Gym =
        Gym(
            id = entity.id,
            name = entity.name,
            address = entity.address,
            coordinates = Coordinates(
                latitude = entity.latitudeMicro / Coordinates.MICRODEGREES_PER_DEGREE,
                longitude = entity.longitudeMicro / Coordinates.MICRODEGREES_PER_DEGREE,
            ),
            equipmentTags = entity.equipmentTags.takeIf { it.isNotEmpty() }?.split(TAG_SEPARATOR).orEmpty(),
            userNotes = entity.userNotes,
            createdAt = entity.createdAt,
        )

    fun toEntity(gym: Gym): GymEntity =
        GymEntity(
            id = gym.id,
            name = gym.name,
            address = gym.address,
            latitudeMicro = (gym.coordinates.latitude * Coordinates.MICRODEGREES_PER_DEGREE).roundToInt(),
            longitudeMicro = (gym.coordinates.longitude * Coordinates.MICRODEGREES_PER_DEGREE).roundToInt(),
            equipmentTags = gym.equipmentTags.joinToString(TAG_SEPARATOR),
            userNotes = gym.userNotes,
            createdAt = gym.createdAt,
        )

    private companion object {
        const val TAG_SEPARATOR = "\n"
    }
}
