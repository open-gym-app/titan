package com.titan.core.domain.gym

import com.titan.core.domain.model.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Haversine against known great-circle distances, within the issue's ±10 m.
 *
 * The expected figures are for a sphere of [GeoDistance.EARTH_RADIUS_KM]: the three analytic
 * ones follow from the radius alone (a degree of arc is `R·π/180`), and the two city pairs were
 * computed with both the Haversine and the spherical law of cosines, which agree to the
 * millimetre. They are *not* the ellipsoidal figures a map app quotes, which differ by up to
 * half a percent.
 */
class GeoDistanceTest {

    private fun assertDistance(
        expectedKm: Double,
        from: Coordinates,
        to: Coordinates,
    ) {
        assertEquals(expectedKm, GeoDistance.haversineKm(from, to), TOLERANCE_KM)
        assertEquals("distance is symmetric", expectedKm, GeoDistance.haversineKm(to, from), TOLERANCE_KM)
    }

    @Test
    fun `one degree of latitude along a meridian is R times pi over 180`() {
        assertDistance(111.195080, Coordinates(0.0, 0.0), Coordinates(1.0, 0.0))
    }

    @Test
    fun `a quarter of the equator is R times pi over 2`() {
        assertDistance(10_007.557221, Coordinates(0.0, 0.0), Coordinates(0.0, 90.0))
    }

    @Test
    fun `pole to pole is half the circumference`() {
        assertDistance(20_015.114442, Coordinates(90.0, 0.0), Coordinates(-90.0, 0.0))
    }

    @Test
    fun `London to Paris`() {
        assertDistance(343.556535, Coordinates(51.5074, -0.1278), Coordinates(48.8566, 2.3522))
    }

    @Test
    fun `New York to Los Angeles`() {
        assertDistance(3_935.751691, Coordinates(40.7128, -74.0060), Coordinates(34.0522, -118.2437))
    }

    @Test
    fun `a city-block distance is accurate to the metre`() {
        assertDistance(1.200015, Coordinates(51.5007, -0.1246), Coordinates(51.5014, -0.1419))
    }

    @Test
    fun `the short way across the antimeridian is taken`() {
        assertDistance(111.195080, Coordinates(0.0, 179.5), Coordinates(0.0, -179.5))
    }

    @Test
    fun `a point is no distance from itself`() {
        val here = Coordinates(51.5074, -0.1278)

        assertEquals(0.0, GeoDistance.haversineKm(here, here), 0.0)
    }

    @Test
    fun `antipodal points do not overflow asin`() {
        val distance = GeoDistance.haversineKm(Coordinates(0.0, 0.0), Coordinates(0.0, 180.0))

        assertEquals(20_015.114442, distance, TOLERANCE_KM)
    }

    private companion object {
        /** ±10 m. */
        const val TOLERANCE_KM = 0.010
    }
}
