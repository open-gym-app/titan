package com.titan.core.domain.gym

import com.titan.core.domain.model.Coordinates
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance between two points, by the Haversine formula.
 *
 * Treats the Earth as a sphere of its mean radius, which is within about 0.5% of the true
 * (ellipsoidal) distance — tens of metres over the few kilometres a "gyms near me" list spans,
 * and well inside what a pin on a map can claim. Haversine rather than the spherical law of
 * cosines because it stays accurate for the short distances this is used for.
 */
object GeoDistance {

    /** The IUGG mean Earth radius, in kilometres. */
    const val EARTH_RADIUS_KM = 6_371.0088

    /** Distance from [from] to [to] in kilometres. Symmetric, and 0 for the same point. */
    fun haversineKm(
        from: Coordinates,
        to: Coordinates,
    ): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLat = toLat - fromLat
        val deltaLng = Math.toRadians(to.longitude - from.longitude)

        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(fromLat) * cos(toLat) * sin(deltaLng / 2) * sin(deltaLng / 2)

        // Rounding can leave `a` a hair past 1 for antipodal points, which asin cannot take.
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }
}
