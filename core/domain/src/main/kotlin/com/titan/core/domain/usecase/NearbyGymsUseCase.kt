package com.titan.core.domain.usecase

import com.titan.core.domain.gym.GeoDistance
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.NearbyGym
import com.titan.core.domain.repository.GymRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The saved gyms within a radius of a location, nearest first.
 *
 * Computed in Kotlin over the whole directory rather than in SQL: a user's own gym list is a
 * handful of rows, SQLite has no trigonometry to run Haversine with, and a bounding-box
 * pre-filter would add a second code path to keep in step with the exact one.
 */
class NearbyGymsUseCase(private val gymRepository: GymRepository) {

    /**
     * Gyms no further than [radiusKm] from [userLocation], ascending by distance, re-emitted as
     * the directory changes. A gym exactly [radiusKm] away is included; gyms at the same
     * distance keep the repository's order.
     *
     * @throws IllegalArgumentException if [radiusKm] is not a positive, finite number.
     */
    operator fun invoke(
        userLocation: Coordinates,
        radiusKm: Double = DEFAULT_RADIUS_KM,
    ): Flow<List<NearbyGym>> {
        require(radiusKm.isFinite() && radiusKm > 0) { "radiusKm must be positive and finite, was $radiusKm" }

        return gymRepository.getAll().map { gyms ->
            gyms
                .map { gym -> NearbyGym(gym, GeoDistance.haversineKm(userLocation, gym.coordinates)) }
                .filter { it.distanceKm <= radiusKm }
                .sortedBy { it.distanceKm }
        }
    }

    companion object {
        const val DEFAULT_RADIUS_KM = 5.0
    }
}
