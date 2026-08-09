package com.opengym.core.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationFormatTest {

    @Test
    fun `a rest is minutes and padded seconds`() {
        assertEquals("2:00", 120.seconds.formatAsClock())
        assertEquals("1:30", 90.seconds.formatAsClock())
        assertEquals("0:45", 45.seconds.formatAsClock())
        assertEquals("0:05", 5.seconds.formatAsClock())
        assertEquals("0:00", 0.seconds.formatAsClock())
    }

    @Test
    fun `the leading field is unpadded and the rest are not`() {
        // `9:05`, not `09:05`: padding the leading field is what makes a single duration read
        // as a stopwatch reading rather than as a time of day.
        assertEquals("9:05", (9.minutes + 5.seconds).formatAsClock())
    }

    @Test
    fun `an hour appears only once there is one`() {
        assertEquals("59:59", (59.minutes + 59.seconds).formatAsClock())
        assertEquals("1:00:00", 1.hours.formatAsClock())
        assertEquals("1:05:30", (1.hours + 5.minutes + 30.seconds).formatAsClock())
        assertEquals("12:00:01", (12.hours + 1.seconds).formatAsClock())
    }

    @Test
    fun `elapsed time always shows its hours field`() {
        // The difference from `formatAsClock`, and the reason both exist: a session header is
        // on screen for the whole workout, and a field that grows from `52:14` to `1:00:00`
        // at the hour mark shoves everything beside it sideways.
        assertEquals("0:00:07", 7.seconds.formatAsElapsed())
        assertEquals("0:52:14", (52.minutes + 14.seconds).formatAsElapsed())
        assertEquals("2:03:00", (2.hours + 3.minutes).formatAsElapsed())
    }

    @Test
    fun `a negative duration is signed rather than fielded into nonsense`() {
        // Not supposed to happen — but "not supposed to" has produced `-1:-30` in every
        // codebase that assumed it, and a clock skewing backwards mid-session produces it
        // here. The magnitude is fielded and the sign is put in front of the whole thing.
        assertEquals("-1:30", (-90).seconds.formatAsClock())
        assertEquals("-0:01:30", (-90).seconds.formatAsElapsed())
    }

    @Test
    fun `a part-second is truncated, not rounded`() {
        // A timer with 400 ms left still says one second. Rounding would show `0:00` for the
        // last half-second of every rest, which reads as the timer having finished early.
        assertEquals("0:01", 1_400.milliseconds.formatAsClock())
        assertEquals("0:00", 999.milliseconds.formatAsClock())
    }
}
