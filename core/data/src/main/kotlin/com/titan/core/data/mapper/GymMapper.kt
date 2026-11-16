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
 * (rounded to the nearest, ~11 cm) or as two nulls for a gym with no pin, and the tag list as one tag per line.
 */
class GymMapper @Inject constructor() {

    fun toDomain(entity: GymEntity): Gym =
        Gym(
            id = entity.id,
            name = entity.name,
            address = entity.address,
            coordinates = coordinatesOf(entity.latitudeMicro, entity.longitudeMicro),
            equipmentTags = entity.equipmentTags.takeIf { it.isNotEmpty() }?.split(TAG_SEPARATOR).orEmpty(),
            userNotes = entity.userNotes,
            createdAt = entity.createdAt,
        )

    fun toEntity(gym: Gym): GymEntity =
        GymEntity(
            id = gym.id,
            name = gym.name,
            address = gym.address,
            latitudeMicro = gym.coordinates?.let { (it.latitude * Coordinates.MICRODEGREES_PER_DEGREE).roundToInt() },
            longitudeMicro = gym.coordinates?.let { (it.longitude * Coordinates.MICRODEGREES_PER_DEGREE).roundToInt() },
            equipmentTags = gym.equipmentTags.joinToString(TAG_SEPARATOR),
            userNotes = gym.userNotes,
            createdAt = gym.createdAt,
        )

    /**
     * The pin, or null unless both halves are stored. The app only ever writes both or neither;
     * half a pin can only come from a file somebody else wrote, and it is not a place.
     */
    private fun coordinatesOf(
        latitudeMicro: Int?,
        longitudeMicro: Int?,
    ): Coordinates? =
        if (latitudeMicro == null || longitudeMicro == null) {
            null
        } else {
            Coordinates(
                latitude = latitudeMicro / Coordinates.MICRODEGREES_PER_DEGREE,
                longitude = longitudeMicro / Coordinates.MICRODEGREES_PER_DEGREE,
            )
        }

    private companion object {
        const val TAG_SEPARATOR = "\n"
    }
}
