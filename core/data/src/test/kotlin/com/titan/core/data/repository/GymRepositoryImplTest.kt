package com.titan.core.data.repository

import com.titan.core.data.db.RoomTestBase
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GymRepositoryImplTest : RoomTestBase() {

    private val repository by lazy { GymRepositoryImpl(db.gymDao()) }

    private fun gym(
        name: String = "Iron Works",
        tags: List<String> = emptyList(),
        notes: String? = null,
        id: Long = 0,
    ) = Gym(
        id = id,
        name = name,
        address = "12 Rue de la Forge",
        coordinates = Coordinates(48.8566, 2.3522),
        equipmentTags = tags,
        userNotes = notes,
        createdAt = CREATED_AT,
    )

    @Test
    fun `a saved gym is read back whole`() =
        runBlocking {
            val id = repository.save(gym(tags = listOf("barbell", "squat rack"), notes = "Quiet before 9"))

            val stored = repository.getById(id)

            assertEquals(gym(tags = listOf("barbell", "squat rack"), notes = "Quiet before 9", id = id), stored)
        }

    @Test
    fun `saving assigns a fresh id each time`() =
        runBlocking {
            val first = repository.save(gym("A"))
            val second = repository.save(gym("B"))

            assertNotEquals(first, second)
            assertEquals(2, repository.getAll().first().size)
        }

    @Test
    fun `saving an existing id edits that gym in place`() =
        runBlocking {
            val id = repository.save(gym("Old name"))

            val returned = repository.save(gym("New name", notes = "moved", id = id))

            assertEquals(id, returned)
            assertEquals(listOf("New name"), repository.getAll().first().map { it.name })
            assertEquals("moved", repository.getById(id)?.userNotes)
        }

    @Test
    fun `deleting removes the gym`() =
        runBlocking {
            val id = repository.save(gym())
            repository.delete(id)

            assertNull(repository.getById(id))
            assertEquals(emptyList<Gym>(), repository.getAll().first())
        }

    @Test
    fun `deleting an unknown id is a no-op`() =
        runBlocking {
            repository.save(gym())
            repository.delete(9_999)

            assertEquals(1, repository.getAll().first().size)
        }

    @Test
    fun `the list is ordered by name ignoring case, then by id`() =
        runBlocking {
            val bravo = repository.save(gym("bravo"))
            repository.save(gym("Alpha"))
            val bravoAgain = repository.save(gym("Bravo"))

            val names = repository.getAll().first()

            assertEquals(listOf("Alpha", "bravo", "Bravo"), names.map { it.name })
            assertEquals(listOf(bravo, bravoAgain), names.drop(1).map { it.id })
        }

    @Test
    fun `tags keep their order and a comma inside a tag survives`() =
        runBlocking {
            val id = repository.save(gym(tags = listOf("sauna, steam", "barbell", "cable")))

            assertEquals(listOf("sauna, steam", "barbell", "cable"), repository.getById(id)?.equipmentTags)
        }

    @Test
    fun `blank and repeated tags are not stored`() =
        runBlocking {
            val id = repository.save(gym(tags = listOf(" barbell ", "", "  ", "Barbell", "rack")))

            assertEquals(listOf("barbell", "rack"), repository.getById(id)?.equipmentTags)
        }

    @Test
    fun `a tag with a newline in it is stored as two tags rather than corrupting the list`() =
        runBlocking {
            val id = repository.save(gym(tags = listOf("rack\nbench", "cable")))

            assertEquals(listOf("rack", "bench", "cable"), repository.getById(id)?.equipmentTags)
        }

    @Test
    fun `an empty address and a null note are kept as they were`() =
        runBlocking {
            val id = repository.save(gym().copy(address = ""))

            val stored = repository.getById(id)
            assertEquals("", stored?.address)
            assertNull(stored?.userNotes)
        }

    @Test
    fun `the list flow emits when a gym is added`() =
        runBlocking {
            assertEquals(0, repository.getAll().first().size)
            repository.save(gym())
            assertEquals(1, repository.getAll().first().size)
        }

    private companion object {
        const val CREATED_AT = 1_788_600_000_000L
    }
}
