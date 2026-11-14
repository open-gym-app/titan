package com.titan.core.data.repository

import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.mapper.GymMapper
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The gym directory through the repository, over a real SQLite. */
class GymRepositoryImplTest : RoomTestBase() {

    private val repository by lazy { GymRepositoryImpl(gymDao = db.gymDao(), gymMapper = GymMapper()) }

    private fun gym(
        name: String,
        tags: List<String> = emptyList(),
    ) = Gym(
        name = name,
        address = "1 Main Street",
        coordinates = Coordinates(48.8566, 2.3522),
        equipmentTags = tags,
        userNotes = "note for $name",
        createdAt = 1_700_000_000_000,
    )

    @Test
    fun `a saved gym comes back with every field`() =
        runBlocking {
            val saved = gym("Iron Works", tags = listOf("squat rack", "plates"))

            val id = repository.save(saved)

            assertEquals(saved.copy(id = id), repository.getById(id).first())
        }

    @Test
    fun `a gym with no tags is saved without them`() =
        runBlocking {
            val id = repository.save(gym("Park Pull-ups"))

            assertEquals(emptyList<String>(), repository.getById(id).first()?.equipmentTags)
        }

    @Test
    fun `new gyms get distinct ids`() =
        runBlocking {
            val first = repository.save(gym("A"))
            val second = repository.save(gym("B"))

            assertEquals(2, setOf(first, second).size)
        }

    @Test
    fun `saving a gym that has an id overwrites it in full and returns the same id`() =
        runBlocking {
            val id = repository.save(gym("Old name", tags = listOf("rack")))

            val returned = repository.save(gym("New name").copy(id = id, userNotes = null))

            assertEquals(id, returned)
            assertEquals(1, repository.getAll().first().size)
            assertEquals(
                gym("New name").copy(id = id, userNotes = null),
                repository.getById(id).first(),
            )
        }

    @Test
    fun `getAll is ordered by name ignoring case, then by id`() =
        runBlocking {
            val b = repository.save(gym("beta"))
            val a = repository.save(gym("Alpha"))
            val twin = repository.save(gym("beta"))

            assertEquals(listOf(a, b, twin), repository.getAll().first().map { it.id })
        }

    @Test
    fun `delete removes only that gym`() =
        runBlocking {
            val keep = repository.save(gym("Keep"))
            val drop = repository.save(gym("Drop"))

            repository.delete(drop)

            assertNull(repository.getById(drop).first())
            assertEquals(listOf(keep), repository.getAll().first().map { it.id })
        }

    @Test
    fun `deleting an unknown id does nothing`() =
        runBlocking {
            val id = repository.save(gym("Keep"))

            repository.delete(id + 100)

            assertEquals(1, repository.getAll().first().size)
        }

    @Test
    fun `an unknown id reads as null`() =
        runBlocking {
            assertNull(repository.getById(42).first())
        }
}
