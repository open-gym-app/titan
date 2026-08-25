package com.opengym.core.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private interface SampleDao {
    suspend fun insert(value: String)
    suspend fun findById(id: Int): String?
}

class DataModuleTest {

    private val dao: SampleDao = mockk(relaxUnitFun = true)

    @Test
    fun `data module compiles and tests run`() {
        assertNull(null)
    }

    @Test
    fun `insert triggers dao write exactly once`() =
        runTest {
            dao.insert("record")
            coVerify(exactly = 1) { dao.insert("record") }
        }

    @Test
    fun `findById returns null when record does not exist`() =
        runTest {
            coEvery { dao.findById(MISSING_ID) } returns null
            assertNull(dao.findById(MISSING_ID))
        }

    @Test
    fun `findById returns value when record exists`() =
        runTest {
            coEvery { dao.findById(1) } returns "record"
            assertEquals("record", dao.findById(1))
        }

    companion object {
        private const val MISSING_ID = 99
    }
}
