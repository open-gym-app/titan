package com.titan.core.domain.model

/**
 * A point on the Earth, in decimal degrees.
 *
 * Doubles here, integers in the database: the domain computes distances with trigonometry and
 * wants floating point, while the schema stores [MICRODEGREES_PER_DEGREE]ths of a degree so the
 * column round-trips through JSON exactly — see docs/PERSISTENCE.md. A microdegree is about
 * 11 cm on the ground, far below what a phone's location fix or a pin dropped on a map can claim.
 *
 * Range checks are `GymValidator`'s, not this class's, for the reason `BodyMeasurement` has
 * none: a half-filled form has to be representable.
 */
data class Coordinates(
    val latitude: Double,
    val longitude: Double,
) {

    companion object {
        const val LATITUDE_MAX = 90.0
        const val LONGITUDE_MAX = 180.0

        /** The unit the database stores a coordinate in. */
        const val MICRODEGREES_PER_DEGREE = 1_000_000.0
    }
}

/**
 * A gym the user has saved to their own directory — somewhere they train, or might.
 *
 * ### Local-first
 *
 * Nothing here is fetched from anywhere: the user types the name and address, drops the pin
 * and tags the equipment, and the whole directory is browsable with no connection. It is the
 * data layer the review and marketplace features build on, and it is exported and restored
 * with the rest of the device.
 *
 * ### Equipment tags
 *
 * Free text, one entry per piece of kit worth knowing about ("squat rack", "dumbbells to
 * 50 kg"). Order is the user's and is kept. Tags are stored in one column, one per line, so
 * `GymValidator` refuses a tag that contains a line break.
 *
 * @property id the row id, or 0 for a gym not saved yet.
 * @property userNotes whatever the user wrote about the place; never shown to anyone else.
 * @property createdAt when the gym was saved, in epoch milliseconds UTC — the caller's clock,
 *   as everywhere else in the data layer.
 */
data class Gym(
    val id: Long = 0,
    val name: String,
    val address: String,
    val coordinates: Coordinates,
    val equipmentTags: List<String> = emptyList(),
    val userNotes: String? = null,
    val createdAt: Long,
)

/** A [Gym] together with how far it is from wherever the distance was measured from. */
data class NearbyGym(
    val gym: Gym,
    val distanceKm: Double,
)
