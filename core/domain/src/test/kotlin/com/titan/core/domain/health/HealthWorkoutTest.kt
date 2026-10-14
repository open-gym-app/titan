package com.titan.core.domain.health

import com.titan.core.domain.model.HealthPermissions
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.model.HealthWorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthWorkoutTest {

    @Test
    fun `a workout must end after it starts`() {
        val result = runCatching { HealthWorkout(5, 5, HealthWorkoutType.OTHER) }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `only the app's own external ids are its own`() {
        assertTrue(HealthWorkout(1, 2, HealthWorkoutType.OTHER, externalId = HealthWorkout.externalIdFor(3)).isOwn)
        assertFalse(HealthWorkout(1, 2, HealthWorkoutType.OTHER, externalId = "x").isOwn)
        assertFalse(HealthWorkout(1, 2, HealthWorkoutType.OTHER).isOwn)
    }

    @Test
    fun `both exercise permissions are required`() {
        assertEquals(
            setOf("android.permission.health.READ_EXERCISE", "android.permission.health.WRITE_EXERCISE"),
            HealthPermissions.REQUIRED,
        )
    }
}
