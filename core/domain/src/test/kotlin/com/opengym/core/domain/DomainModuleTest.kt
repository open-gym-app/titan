package com.opengym.core.domain

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private interface SampleRepository {
    suspend fun fetch(): String
    fun stream(): Flow<Int>
}

class DomainModuleTest {

    private val repository: SampleRepository = mockk()

    @Test
    fun `domain module compiles and tests run`() {
        assertTrue(true)
    }

    @Test
    fun `mocked suspend call returns expected value`() = runTest {
        coEvery { repository.fetch() } returns "ok"
        assertEquals("ok", repository.fetch())
    }

    @Test
    fun `flow emits expected sequence via turbine`() = runTest {
        val expected = listOf(1, 2, 3)
        coEvery { repository.stream() } returns flowOf(*expected.toTypedArray())

        repository.stream().test {
            expected.forEach { assertEquals(it, awaitItem()) }
            awaitComplete()
        }
    }

    @Test
    fun `flow error path propagates exception`() = runTest {
        coEvery { repository.stream() } returns flow { throw RuntimeException("network error") }

        repository.stream().test {
            assertEquals("network error", awaitError().message)
        }
    }

    @Test
    fun `negative - empty list is not non-empty`() {
        val empty = emptyList<Int>()
        assertTrue(empty.isEmpty())
        assertFalse(empty.isNotEmpty())
    }
}
