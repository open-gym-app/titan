package com.titan.core.domain.gym

import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym
import com.titan.core.domain.repository.GymRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyGymsUseCaseTest {

    private val repository = FakeGymRepository()
    private val useCase = NearbyGymsUseCase(repository)

    private fun gym(
        id: Long,
        name: String,
        at: Coordinates,
    ) = Gym(id = id, name = name, address = "", coordinates = at, createdAt = 0L)

    @Test
    fun `gyms inside the radius come back nearest first`() =
        runTest {
            repository.gyms.value = listOf(
                gym(1, "Far", offsetKm(3.0)),
                gym(2, "Near", offsetKm(0.5)),
                gym(3, "Middle", offsetKm(1.5)),
            )

            val result = useCase(HOME, radiusKm = 5.0).first()

            assertEquals(listOf("Near", "Middle", "Far"), result.map { it.gym.name })
            assertEquals(0.5, result[0].distanceKm, 0.01)
        }

    @Test
    fun `gyms beyond the radius are dropped`() =
        runTest {
            repository.gyms.value = listOf(gym(1, "In", offsetKm(4.0)), gym(2, "Out", offsetKm(6.0)))

            assertEquals(listOf("In"), useCase(HOME, radiusKm = 5.0).first().map { it.gym.name })
        }

    @Test
    fun `the default radius is five kilometres`() =
        runTest {
            repository.gyms.value = listOf(gym(1, "In", offsetKm(4.9)), gym(2, "Out", offsetKm(5.1)))

            assertEquals(listOf("In"), useCase(HOME).first().map { it.gym.name })
        }

    @Test
    fun `a gym exactly at the user is inside even a zero radius`() =
        runTest {
            repository.gyms.value = listOf(gym(1, "Here", HOME), gym(2, "There", offsetKm(1.0)))

            assertEquals(listOf("Here"), useCase(HOME, radiusKm = 0.0).first().map { it.gym.name })
        }

    @Test
    fun `no saved gyms is an empty list`() =
        runTest {
            assertTrue(useCase(HOME).first().isEmpty())
        }

    @Test
    fun `a negative, infinite or NaN radius is refused`() {
        assertThrows(IllegalArgumentException::class.java) { useCase(HOME, radiusKm = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { useCase(HOME, radiusKm = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { useCase(HOME, radiusKm = Double.NaN) }
    }

    @Test
    fun `the list follows the directory when a gym is added`() =
        runTest {
            val flow = useCase(HOME)
            assertTrue(flow.first().isEmpty())

            repository.gyms.value = listOf(gym(1, "New", offsetKm(1.0)))

            assertEquals(1, flow.first().size)
        }

    private fun offsetKm(km: Double): Coordinates = Coordinates(HOME.latitude + km / KM_PER_DEGREE, HOME.longitude)

    private companion object {
        val HOME = Coordinates(48.8566, 2.3522)
        const val KM_PER_DEGREE = 111.195
    }
}

private class FakeGymRepository : GymRepository {
    val gyms = MutableStateFlow<List<Gym>>(emptyList())

    override fun getAll(): Flow<List<Gym>> = gyms

    override suspend fun getById(id: Long): Gym? = gyms.value.firstOrNull { it.id == id }

    override suspend fun save(gym: Gym): Long {
        gyms.value = gyms.value.filterNot { it.id == gym.id } + gym
        return gym.id
    }

    override suspend fun delete(id: Long) {
        gyms.value = gyms.value.filterNot { it.id == id }
    }
}
