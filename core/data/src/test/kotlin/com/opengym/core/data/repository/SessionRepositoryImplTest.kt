package com.opengym.core.data.repository

import app.cash.turbine.test
import com.opengym.core.data.db.dao.SessionDao
import com.opengym.core.data.db.entity.SessionEntity
import com.opengym.core.domain.model.WorkoutSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRepositoryImplTest {

    private val dao: SessionDao = mockk(relaxUnitFun = true)
    private val repository = SessionRepositoryImpl(dao)

    private val entity = SessionEntity(id = 1L, startedAt = 1000L, finishedAt = null, notes = "")
    private val domain = WorkoutSession(id = 1L, startedAt = 1000L, finishedAt = null, notes = "")

    @Test
    fun `getAll maps entities to domain models`() = runTest {
        every { dao.getAll() } returns flowOf(listOf(entity))

        repository.getAll().test {
            assertEquals(listOf(domain), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getActive returns null when no active session`() = runTest {
        every { dao.getActive() } returns flowOf(null)

        repository.getActive().test {
            assertNull(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getActive returns active session when finishedAt is null`() = runTest {
        every { dao.getActive() } returns flowOf(entity)

        repository.getActive().test {
            assertEquals(domain, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `insert delegates to dao`() = runTest {
        coEvery { dao.insert(entity) } returns 1L

        val id = repository.insert(domain)

        assertEquals(1L, id)
        coVerify(exactly = 1) { dao.insert(entity) }
    }

    @Test
    fun `update delegates to dao`() = runTest {
        val finished = domain.copy(finishedAt = 2000L)
        val finishedEntity = entity.copy(finishedAt = 2000L)

        repository.update(finished)

        coVerify(exactly = 1) { dao.update(finishedEntity) }
    }

    @Test
    fun `delete delegates to dao`() = runTest {
        repository.delete(1L)
        coVerify(exactly = 1) { dao.delete(1L) }
    }
}
