package com.opengym.core.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
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
    fun `an estimate is drawn as an approximation, not as a clock`() {
        // The distinction the third format exists for: `55:00` would read as a measured
        // time next to the session timer, and the figure behind it is a per-set allowance.
        assertEquals("~55m", 55.minutes.formatAsEstimate())
        assertEquals("~30m", 30.minutes.formatAsEstimate())
    }

    @Test
    fun `an estimate rounds to the nearest five minutes`() {
        // Roughly the precision the formula has. A row reading `~53m` claims more than
        // TemplateDuration knows.
        assertEquals("~55m", (53.minutes + 20.seconds).formatAsEstimate())
        assertEquals("~50m", (52.minutes + 29.seconds).formatAsEstimate())
        assertEquals("~55m", (52.minutes + 30.seconds).formatAsEstimate())
    }

    @Test
    fun `an estimate gains an hours field, with its minutes padded`() {
        assertEquals("~1h 00m", 1.hours.formatAsEstimate())
        assertEquals("~1h 05m", (1.hours + 5.minutes).formatAsEstimate())
        assertEquals("~1h 35m", (1.hours + 34.minutes).formatAsEstimate())
        assertEquals("~2h 00m", (1.hours + 58.minutes).formatAsEstimate())
    }

    @Test
    fun `a short estimate never rounds down to nothing`() {
        // One exercise is not a workout of no length. The floor is one step, so the row
        // says `~5m` rather than `~0m`, which reads as a template that does nothing.
        assertEquals("~5m", 45.seconds.formatAsEstimate())
        assertEquals("~5m", 1.seconds.formatAsEstimate())
    }

    @Test
    fun `nothing to pace estimates as zero`() {
        // A template whose exercises were removed underneath it. Distinct from the case
        // above: there is genuinely nothing, and the row drops the figure rather than
        // showing this — see TemplateRow.
        assertEquals("~0m", Duration.ZERO.formatAsEstimate())
        assertEquals("~0m", (-90).seconds.formatAsEstimate())
    }

    @Test
    fun `a part-second is truncated, not rounded`() {
        // A timer with 400 ms left still says one second. Rounding would show `0:00` for the
        // last half-second of every rest, which reads as the timer having finished early.
        assertEquals("0:01", 1_400.milliseconds.formatAsClock())
        assertEquals("0:00", 999.milliseconds.formatAsClock())
    }
}
