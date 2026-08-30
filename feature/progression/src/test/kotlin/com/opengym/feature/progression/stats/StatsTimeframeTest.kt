package com.opengym.feature.progression.stats

import com.opengym.core.domain.stats.TrainingWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The window each timeframe resolves to.
 *
 * Worth its own file because two things depend on it and neither is obvious: the aggregates
 * refuse an empty period, and the charts refuse a period narrower than two weekly buckets. A
 * timeframe that produced either would be a screen that crashed or a screen that drew nothing,
 * for a user who did nothing wrong but tap `7D`.
 */
class StatsTimeframeTest {

    @Test
    fun `a fixed-length window ends today and counts back inclusively`() {
        val period = StatsTimeframe.MONTH.period(TODAY)

        // Thirty days including today, not thirty days before it.
        assertEquals(LocalDate.of(2026, 3, 30), period.start)
        assertEquals(TODAY, period.endInclusive)
    }

    @Test
    fun `all time opens on the day the user first trained`() {
        val firstDay = LocalDate.of(2024, 1, 15)

        val period = StatsTimeframe.ALL.period(TODAY, firstTrainingDay = firstDay)

        assertEquals(firstDay, period.start)
        assertEquals(TODAY, period.endInclusive)
    }

    @Test
    fun `all time is today alone for a user with no history to open on`() {
        val period = StatsTimeframe.ALL.period(TODAY, firstTrainingDay = null)

        // Not an empty range, which `TrainingWeek.snap` rejects: a user who has never trained
        // is exactly the caller that would otherwise crash the screen meant to prompt them.
        assertEquals(TODAY..TODAY, period.start..period.endInclusive)
        assertTrue(period.start <= period.endInclusive)
    }

    @Test
    fun `all time ignores a first training day in the future`() {
        // Nothing writes one, but a device whose clock has been corrected backwards can leave a
        // session stamped ahead of today, and a period that started after it ends is rejected.
        val period = StatsTimeframe.ALL.period(TODAY, firstTrainingDay = TODAY.plusDays(30))

        assertTrue(period.start <= period.endInclusive)
    }

    @Test
    fun `the shortest window spans two weekly buckets on every day but Sunday`() {
        // "The last seven days" ends on whatever day it is, so it crosses a Monday on six days
        // out of seven and the chart has two marks to draw a line between. Read *on* a Sunday
        // it is exactly one week, and the hero chart then says there is not enough to plot —
        // which is true of a seven-day series bucketed by week, and which the figure and its
        // comparison chip are unaffected by. See `MIN_PLOTTABLE_ENTRIES`.
        assertEquals(2, TrainingWeek.countIn(TrainingWeek.snap(StatsTimeframe.WEEK.period(TODAY))))

        val sunday = LocalDate.of(2026, 4, 26)
        assertEquals(1, TrainingWeek.countIn(TrainingWeek.snap(StatsTimeframe.WEEK.period(sunday))))
    }

    @Test
    fun `every timeframe resolves to a period the aggregates accept`() {
        StatsTimeframe.entries.forEach { timeframe ->
            val period = timeframe.period(TODAY, firstTrainingDay = LocalDate.of(2024, 1, 15))

            // `snap` requires a non-empty period and throws on one that is not — this is the
            // assertion that a new timeframe cannot be added without a window behind it.
            assertTrue(timeframe.name, TrainingWeek.countIn(period) >= 1)
        }
    }

    private companion object {
        /** A Tuesday, so a seven-day window crosses a Monday. */
        val TODAY: LocalDate = LocalDate.of(2026, 4, 28)
    }
}
