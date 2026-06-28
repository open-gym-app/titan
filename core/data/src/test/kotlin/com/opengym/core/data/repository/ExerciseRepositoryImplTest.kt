package com.opengym.core.data.repository

import app.cash.turbine.test
import com.opengym.core.data.db.dao.ExerciseDao
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseRepositoryImplTest {

    private val dao: ExerciseDao = mockk()
    private val repository = ExerciseRepositoryImpl(dao)

    private val entity = ExerciseEntity(
        id = 1L,
        name = "Bench Press",
        exerciseType = "FREE_WEIGHT",
        primaryMuscleGroup = "Chest",
    )
    private val domain = Exercise(
        id = 1L,
        name = "Bench Press",
        type = ExerciseType.FREE_WEIGHT,
        primaryMuscleGroup = "Chest",
    )

    @Test
    fun `getAll maps entities to domain models`() = runTest {
        every { dao.getAll() } returns flowOf(listOf(entity))

        repository.getAll().test {
            assertEquals(listOf(domain), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getById returns null when entity not found`() = runTest {
        every { dao.getById(99L) } returns flowOf(null)

        repository.getById(99L).test {
            assertEquals(null, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getByType filters by type name`() = runTest {
        every { dao.getByType("FREE_WEIGHT") } returns flowOf(listOf(entity))

        repository.getByType(ExerciseType.FREE_WEIGHT).test {
            assertEquals(listOf(domain), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `insert delegates to dao with mapped entity`() = runTest {
        coEvery { dao.insert(entity) } returns 1L

        val id = repository.insert(domain)

        assertEquals(1L, id)
        coVerify(exactly = 1) { dao.insert(entity) }
    }
}
