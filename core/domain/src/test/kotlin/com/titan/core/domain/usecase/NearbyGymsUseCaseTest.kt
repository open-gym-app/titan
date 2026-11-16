package com.titan.core.domain.usecase

import app.cash.turbine.test
import com.titan.core.domain.gym.GeoDistance
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym
import com.titan.core.domain.repository.GymRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NearbyGymsUseCaseTest {

    private val here = Coordinates(51.5074, -0.1278)

    /** `km` kilometres due north of [here]: a degree of latitude is a fixed arc on a sphere. */
    private fun northOf(km: Double) =
        Coordinates(here.latitude + Math.toDegrees(km / GeoDistance.EARTH_RADIUS_KM), here.longitude)

    private fun gym(
        id: Long,
        coordinates: Coordinates?,
    ) = Gym(id = id, name = "Gym $id", address = "somewhere", coordinates = coordinates, createdAt = 0)

    private class FakeGymRepository(initial: List<Gym>) : GymRepository {
        val gyms = MutableStateFlow(initial)

        override fun getAll(): Flow<List<Gym>> = gyms

        override fun getById(id: Long): Flow<Gym?> = gyms.map { list -> list.firstOrNull { it.id == id } }

        override suspend fun save(gym: Gym): Long = error("not used")

        override suspend fun delete(id: Long) = error("not used")
    }

    /** A gym with no pin has no distance, so it is not "nearby" at any radius. */
    @Test
    fun `a gym saved without coordinates is never nearby`() =
        runTest {
            val repository = FakeGymRepository(listOf(gym(1, null), gym(2, northOf(1.0))))

            val nearby = NearbyGymsUseCase(repository)(userLocation = here, radiusKm = 20_000.0)

            nearby.test {
                assertEquals(listOf(2L), awaitItem().map { it.gym.id })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `only gyms inside the radius come back, nearest first`() =
        runTest {
            val repository = FakeGymRepository(
                listOf(
                    gym(1, northOf(4.0)),
                    gym(2, northOf(1.0)),
                    gym(3, northOf(5.5)),
                    gym(4, northOf(20.0)),
                    gym(5, northOf(2.5)),
                ),
            )

            val nearby = NearbyGymsUseCase(repository)(userLocation = here, radiusKm = 5.0)

            nearby.test {
                val result = awaitItem()
                assertEquals(listOf(2L, 5L, 1L), result.map { it.gym.id })
                assertEquals(1.0, result[0].distanceKm, 0.001)
                assertEquals(2.5, result[1].distanceKm, 0.001)
                assertEquals(4.0, result[2].distanceKm, 0.001)
            }
        }

    @Test
    fun `the default radius is five kilometres`() =
        runTest {
            val repository = FakeGymRepository(listOf(gym(1, northOf(4.9)), gym(2, northOf(5.1))))

            NearbyGymsUseCase(repository)(here).test {
                assertEquals(listOf(1L), awaitItem().map { it.gym.id })
            }
        }

    @Test
    fun `a gym exactly on the boundary is included`() =
        runTest {
            val spot = northOf(3.0)
            val exact = GeoDistance.haversineKm(here, spot)
            val repository = FakeGymRepository(listOf(gym(1, spot)))

            NearbyGymsUseCase(repository)(here, radiusKm = exact).test {
                assertEquals(listOf(1L), awaitItem().map { it.gym.id })
            }
        }

    @Test
    fun `a gym at the user's own location is zero kilometres away`() =
        runTest {
            val repository = FakeGymRepository(listOf(gym(1, here)))

            NearbyGymsUseCase(repository)(here).test {
                assertEquals(0.0, awaitItem().single().distanceKm, 0.0)
            }
        }

    @Test
    fun `an empty directory has nobody nearby`() =
        runTest {
            NearbyGymsUseCase(FakeGymRepository(emptyList()))(here).test {
                assertEquals(emptyList<Long>(), awaitItem().map { it.gym.id })
            }
        }

    @Test
    fun `the list follows the directory as it changes`() =
        runTest {
            val repository = FakeGymRepository(emptyList())

            NearbyGymsUseCase(repository)(here).test {
                assertEquals(0, awaitItem().size)

                repository.gyms.value = listOf(gym(1, northOf(1.0)))

                assertEquals(listOf(1L), awaitItem().map { it.gym.id })
            }
        }

    @Test
    fun `a radius that is not positive and finite is refused`() {
        val useCase = NearbyGymsUseCase(FakeGymRepository(emptyList()))

        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { radius ->
            assertThrows(IllegalArgumentException::class.java) { useCase(here, radius) }
        }
    }
}
