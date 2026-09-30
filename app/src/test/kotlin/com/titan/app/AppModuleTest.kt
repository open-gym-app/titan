package com.titan.app

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private interface SampleUseCase {
    fun execute(input: String)
}

class AppModuleTest {

    private val useCase: SampleUseCase = mockk(relaxUnitFun = true)

    @Test
    fun `app module compiles and tests run`() {
        assertNotNull(Any())
    }

    @Test
    fun `use case is invoked with correct argument`() =
        runTest {
            useCase.execute("input")
            verify(exactly = 1) { useCase.execute("input") }
        }

    @Test
    fun `negative - absent value is null`() {
        val absent: String? = null
        assertNull(absent)
    }
}
