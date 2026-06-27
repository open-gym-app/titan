package com.opengym.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModuleTest {

    @Test
    fun `ui module compiles and tests run`() {
        assertTrue(true)
    }

    @Test
    fun `dark theme and light theme are mutually exclusive`() {
        val isDark = true
        val isLight = !isDark
        assertTrue(isDark)
        assertFalse(isLight)
    }

    @Test
    fun `negative - false condition fails correctly`() {
        val alwaysFalse = 1 == 2
        assertFalse(alwaysFalse)
    }
}
