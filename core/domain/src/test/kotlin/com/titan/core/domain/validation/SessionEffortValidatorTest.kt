package com.titan.core.domain.validation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEffortValidatorTest {

    @Test
    fun `neither figure is required`() {
        assertTrue(SessionEffortValidator.validate(calories = null, rating = null).isValid)
    }

    @Test
    fun `a rating is one to five stars`() {
        (SessionEffortValidator.RATING_MIN..SessionEffortValidator.RATING_MAX).forEach { rating ->
            assertTrue(SessionEffortValidator.validate(calories = null, rating = rating).isValid)
        }
        assertFalse(SessionEffortValidator.validate(calories = null, rating = 0).isValid)
        assertFalse(SessionEffortValidator.validate(calories = null, rating = 6).isValid)
    }

    @Test
    fun `calories are a positive figure under the ceiling`() {
        assertTrue(
            SessionEffortValidator.validate(calories = SessionEffortValidator.CALORIES_MAX, rating = null).isValid,
        )
        assertFalse(SessionEffortValidator.validate(calories = 0, rating = null).isValid)
        assertFalse(
            SessionEffortValidator.validate(calories = SessionEffortValidator.CALORIES_MAX + 1, rating = null).isValid,
        )
    }

    @Test
    fun `both violations are reported at once`() {
        val reason = SessionEffortValidator.validate(calories = -5, rating = 9).reasonOrNull().orEmpty()

        assertTrue(reason.contains("calories") && reason.contains("rating"))
    }
}
