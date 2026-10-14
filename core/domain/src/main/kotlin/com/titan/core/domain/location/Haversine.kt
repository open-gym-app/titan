package com.titan.core.domain.location

import com.titan.core.domain.model.Coordinates
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance between two [Coordinates], by the haversine formula.
 *
 * Treats the Earth as a sphere of its mean radius, which is within about 0.5% of the true
 * distance anywhere — a few tens of metres over the 5 km a gym search covers, and far
 * inside the accuracy of the position the user's phone reports.
 */
object Haversine {

    /** The mean Earth radius in kilometres (IUGG). */
    const val EARTH_RADIUS_KM = 6371.0088

    fun distanceKm(
        from: Coordinates,
        to: Coordinates,
    ): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val halfDeltaLat = (lat2 - lat1) / 2
        val halfDeltaLng = Math.toRadians(to.longitude - from.longitude) / 2

        val a = sin(halfDeltaLat) * sin(halfDeltaLat) +
            cos(lat1) * cos(lat2) * sin(halfDeltaLng) * sin(halfDeltaLng)
        // Rounding can push `a` a hair above 1 for antipodal points, which asin would turn into NaN.
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }
}
