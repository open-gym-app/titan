package com.titan.core.domain.gym

import com.titan.core.domain.location.Haversine
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.NearbyGym
import com.titan.core.domain.repository.GymRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The saved gyms within a radius of the user, nearest first.
 *
 * A flow, so a gym saved while the list is open appears in it. A gym exactly [radiusKm] away
 * is within it; gyms the same distance away keep the repository's order (by name).
 */
class NearbyGymsUseCase(private val gyms: GymRepository) {

    operator fun invoke(
        userLocation: Coordinates,
        radiusKm: Double = DEFAULT_RADIUS_KM,
    ): Flow<List<NearbyGym>> {
        require(radiusKm.isFinite() && radiusKm >= 0.0) { "radiusKm must be finite and >= 0, was $radiusKm" }
        return gyms.getAll().map { all ->
            all.map { NearbyGym(it, Haversine.distanceKm(userLocation, it.coordinates)) }
                .filter { it.distanceKm <= radiusKm }
                .sortedBy { it.distanceKm }
        }
    }

    companion object {
        const val DEFAULT_RADIUS_KM = 5.0
    }
}
