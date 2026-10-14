package com.titan.core.domain.location

import com.titan.core.domain.model.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Reference distances computed independently with R = 6371.0088 km; tolerance is 10 m. */
class HaversineTest {

    private fun km(
        from: Coordinates,
        to: Coordinates,
    ) = Haversine.distanceKm(from, to)

    private fun assertKm(
        expected: Double,
        actual: Double,
    ) = assertEquals(expected, actual, TOLERANCE_KM)

    @Test
    fun `Paris to London`() = assertKm(343.5565, km(PARIS, LONDON))

    @Test
    fun `New York to Los Angeles`() = assertKm(3935.7517, km(NEW_YORK, LOS_ANGELES))

    @Test
    fun `Sydney to Melbourne crosses the southern hemisphere`() = assertKm(713.4285, km(SYDNEY, MELBOURNE))

    @Test
    fun `one degree of latitude at the equator`() = assertKm(111.1951, km(Coordinates(0.0, 0.0), Coordinates(1.0, 0.0)))

    @Test
    fun `one degree of longitude at the equator`() =
        assertKm(111.1951, km(Coordinates(0.0, 0.0), Coordinates(0.0, 1.0)))

    @Test
    fun `a short city hop of about a kilometre`() = assertKm(1.0008, km(PARIS, Coordinates(48.8656, 2.3522)))

    @Test
    fun `two points either side of the antimeridian are close, not half the planet apart`() =
        assertKm(111.1951, km(Coordinates(0.0, 179.5), Coordinates(0.0, -179.5)))

    @Test
    fun `antipodes are half the circumference apart and never NaN`() {
        val distance = km(Coordinates(0.0, 0.0), Coordinates(0.0, 180.0))
        assertFalse(distance.isNaN())
        assertKm(20015.1144, distance)
    }

    @Test
    fun `the same point is zero apart`() = assertEquals(0.0, km(PARIS, PARIS), 0.0)

    @Test
    fun `distance is symmetric`() = assertEquals(km(PARIS, LONDON), km(LONDON, PARIS), 1e-9)

    @Test
    fun `the poles are a half circumference apart`() =
        assertKm(20015.1144, km(Coordinates(90.0, 0.0), Coordinates(-90.0, 0.0)))

    private companion object {
        const val TOLERANCE_KM = 0.01
        val PARIS = Coordinates(48.8566, 2.3522)
        val LONDON = Coordinates(51.5074, -0.1278)
        val NEW_YORK = Coordinates(40.7128, -74.0060)
        val LOS_ANGELES = Coordinates(34.0522, -118.2437)
        val SYDNEY = Coordinates(-33.8688, 151.2093)
        val MELBOURNE = Coordinates(-37.8136, 144.9631)
    }
}
