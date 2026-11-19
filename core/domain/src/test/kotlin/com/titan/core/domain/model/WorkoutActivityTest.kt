package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutActivityTest {

    @Test
    fun `a session of lifts is strength training`() {
        assertEquals(WorkoutActivity.STRENGTH_TRAINING, WorkoutActivity.of(listOf(Discipline.STRENGTH)))
    }

    @Test
    fun `a session of only powerlifting and olympic lifts is weightlifting`() {
        val disciplines = listOf(Discipline.POWERLIFTING, Discipline.OLYMPIC_WEIGHTLIFTING)

        assertEquals(WorkoutActivity.WEIGHTLIFTING, WorkoutActivity.of(disciplines))
    }

    @Test
    fun `a session of only stretches is stretching`() {
        val disciplines = listOf(Discipline.STRETCHING, Discipline.STRETCHING)

        assertEquals(WorkoutActivity.STRETCHING, WorkoutActivity.of(disciplines))
    }

    @Test
    fun `a session of only cardio is other, because cardio does not say which machine`() {
        assertEquals(WorkoutActivity.OTHER, WorkoutActivity.of(listOf(Discipline.CARDIO)))
    }

    @Test
    fun `a mixed session is strength training`() {
        val disciplines = listOf(Discipline.POWERLIFTING, Discipline.STRETCHING, Discipline.CARDIO)

        assertEquals(WorkoutActivity.STRENGTH_TRAINING, WorkoutActivity.of(disciplines))
    }

    @Test
    fun `an exercise with no discipline counts as a lift`() {
        assertEquals(WorkoutActivity.STRENGTH_TRAINING, WorkoutActivity.of(listOf(Discipline.STRETCHING, null)))
    }

    @Test
    fun `no exercises at all is strength training`() {
        assertEquals(WorkoutActivity.STRENGTH_TRAINING, WorkoutActivity.of(emptyList()))
    }
}
