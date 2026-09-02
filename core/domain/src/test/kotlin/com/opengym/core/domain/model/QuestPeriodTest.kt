package com.opengym.core.domain.model

import com.opengym.core.domain.stats.TrainingWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * Where a quest's period opens and closes.
 *
 * The boundary is the part of #105 this build deviates from — the issue asks for local
 * midnight, this cuts in **UTC** like `TrainingWeek` and `StreakCalculator` — so it is tested
 * rather than assumed. See [QuestPeriod] for the argument.
 */
class QuestPeriodTest {

    // ─── Weekly ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a week opens on Monday`() {
        // Wednesday 3 September 2025.
        val start = QuestPeriod.WEEKLY.startOf(LocalDate.of(2025, Month.SEPTEMBER, 3))

        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertEquals(LocalDate.of(2025, Month.SEPTEMBER, 1), start)
    }

    @Test
    fun `a week closes on the Sunday after it`() {
        val days = QuestPeriod.WEEKLY.rangeOf(LocalDate.of(2025, Month.SEPTEMBER, 3))

        assertEquals(LocalDate.of(2025, Month.SEPTEMBER, 1), days.start)
        assertEquals(LocalDate.of(2025, Month.SEPTEMBER, 7), days.endInclusive)
        assertEquals(DayOfWeek.SUNDAY, days.endInclusive.dayOfWeek)
    }

    @Test
    fun `a Monday opens its own week rather than the one before`() {
        val monday = LocalDate.of(2025, Month.SEPTEMBER, 1)

        assertEquals(monday, QuestPeriod.WEEKLY.startOf(monday))
    }

    @Test
    fun `the week is the one TrainingWeek already defines`() {
        // The rule stated twice would be two rules. `TrainingWeek`'s doc asks for exactly this;
        // a quest week that drifted from the volume chart's week would make "this week" mean
        // two different things on two screens of one app.
        var day = LocalDate.of(2025, Month.JANUARY, 1)
        repeat(400) {
            assertEquals("$day", TrainingWeek.startOf(day), QuestPeriod.WEEKLY.startOf(day))
            day = day.plusDays(1)
        }
    }

    @Test
    fun `every week is seven days long`() {
        var day = LocalDate.of(2025, Month.JANUARY, 1)
        repeat(400) {
            val days = QuestPeriod.WEEKLY.rangeOf(day)
            assertEquals(
                "$day",
                TrainingWeek.DAYS,
                days.start.until(days.endInclusive.plusDays(1), java.time.temporal.ChronoUnit.DAYS),
            )
            day = day.plusDays(1)
        }
    }

    // ─── Monthly ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a month opens on the first`() {
        assertEquals(
            LocalDate.of(2025, Month.SEPTEMBER, 1),
            QuestPeriod.MONTHLY.startOf(LocalDate.of(2025, Month.SEPTEMBER, 17)),
        )
    }

    @Test
    fun `a month closes on its own last day`() {
        assertEquals(
            LocalDate.of(2025, Month.SEPTEMBER, 30),
            QuestPeriod.MONTHLY.endOf(LocalDate.of(2025, Month.SEPTEMBER, 17)),
        )
    }

    @Test
    fun `February closes on the 28th and on the 29th in a leap year`() {
        assertEquals(
            LocalDate.of(2025, Month.FEBRUARY, 28),
            QuestPeriod.MONTHLY.endOf(LocalDate.of(2025, Month.FEBRUARY, 10)),
        )
        assertEquals(
            LocalDate.of(2024, Month.FEBRUARY, 29),
            QuestPeriod.MONTHLY.endOf(LocalDate.of(2024, Month.FEBRUARY, 10)),
        )
    }

    @Test
    fun `a month is not a whole number of weeks`() {
        // The property that decides how the board reads its aggregates: a month starts and ends
        // mid-week far more often than not, which is why `QuestProgressUseCase` reads scalars
        // over the range rather than summing the Monday-bucketed series the charts draw.
        val ragged = (1..12).count { month ->
            val days = QuestPeriod.MONTHLY.rangeOf(LocalDate.of(2025, month, 15))
            days.start.dayOfWeek != DayOfWeek.MONDAY || days.endInclusive.dayOfWeek != DayOfWeek.SUNDAY
        }

        assertEquals("2025 had a month that fell on whole weeks", 12, ragged)
    }

    // ─── Both ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a period contains the day it was derived from`() {
        QuestPeriod.entries.forEach { period ->
            var day = LocalDate.of(2024, Month.JANUARY, 1)
            repeat(800) {
                assertTrue("$period does not contain $day", day in period.rangeOf(day))
                day = day.plusDays(1)
            }
        }
    }

    @Test
    fun `every day in a period reports the same period`() {
        // What makes the period's first day usable as half of a completion's key: two sessions
        // in one week must complete into the same row, whichever days they fall on.
        QuestPeriod.entries.forEach { period ->
            var day = LocalDate.of(2024, Month.JANUARY, 1)
            repeat(800) {
                val days = period.rangeOf(day)
                var inside = days.start
                while (inside <= days.endInclusive) {
                    assertEquals("$period, from $day", days.start, period.startOf(inside))
                    inside = inside.plusDays(1)
                }
                day = day.plusDays(1)
            }
        }
    }

    @Test
    fun `consecutive periods meet without a gap or an overlap`() {
        // A day that fell in no period would be training the board could never see; a day in
        // two would let one session complete the same quest in both.
        QuestPeriod.entries.forEach { period ->
            var days = period.rangeOf(LocalDate.of(2024, Month.JANUARY, 15))
            repeat(40) {
                val next = period.rangeOf(days.endInclusive.plusDays(1))
                assertEquals("$period leaves a gap or overlaps", days.endInclusive.plusDays(1), next.start)
                days = next
            }
        }
    }
}
