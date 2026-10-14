package com.titan.core.domain.model

/**
 * A point on the Earth in decimal degrees (WGS84).
 *
 * Refuses a latitude outside -90..90 or a longitude outside -180..180, and anything not a
 * number, at construction: every distance computed from one is meaningless otherwise, and a
 * value that cannot be on the globe is a typing mistake or a corrupt row, not a place.
 */
data class Coordinates(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in MIN_LATITUDE..MAX_LATITUDE) { "latitude must be within -90..90, was $latitude" }
        require(longitude in MIN_LONGITUDE..MAX_LONGITUDE) { "longitude must be within -180..180, was $longitude" }
    }

    private companion object {
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
    }
}
