package com.titan.core.domain.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The week boundary the whole progression stack is bucketed on. Every date here is written
 * with its weekday in a comment, because a test of "which Monday is this" that computes the
 * Monday the same way the code does would agree with any answer.
 */
class TrainingWeekTest {

    private companion object {
        /** Monday. The anchor every case below is built from. */
        val MONDAY: LocalDate = LocalDate.of(2024, 10, 7)
        val SUNDAY: LocalDate = LocalDate.of(2024, 10, 13)
    }

    @Test
    fun `weeks open on Monday`() {
        assertEquals(DayOfWeek.MONDAY, TrainingWeek.FIRST_DAY)
    }

    @Test
    fun `a Monday opens its own week`() {
        assertEquals(MONDAY, TrainingWeek.startOf(MONDAY))
    }

    @Test
    fun `every other day of the week rounds back to that Monday`() {
        val starts = (0..6).map { TrainingWeek.startOf(MONDAY.plusDays(it.toLong())) }

        assertEquals(List(7) { MONDAY }, starts)
    }

    @Test
    fun `the Sunday before a Monday belongs to the previous week`() {
        // 2024-09-29 is the Sunday that closes the week before MONDAY's.
        assertEquals(MONDAY.minusDays(7), TrainingWeek.startOf(MONDAY.minusDays(1)))
    }

    @Test
    fun `snapping widens a mid-week period to the whole weeks it touches`() {
        // Wednesday to the Thursday of the week after: two weeks, opened and closed whole.
        val snapped = TrainingWeek.snap(MONDAY.plusDays(2)..MONDAY.plusDays(10))

        assertEquals(MONDAY, snapped.start)
        assertEquals(SUNDAY.plusDays(7), snapped.endInclusive)
    }

    @Test
    fun `snapping a period that is already whole weeks leaves it alone`() {
        val period = MONDAY..SUNDAY

        val snapped = TrainingWeek.snap(period)

        assertEquals(period.start, snapped.start)
        assertEquals(period.endInclusive, snapped.endInclusive)
    }

    @Test
    fun `a single day snaps to the week containing it`() {
        val snapped = TrainingWeek.snap(SUNDAY..SUNDAY)

        assertEquals(MONDAY, snapped.start)
        assertEquals(SUNDAY, snapped.endInclusive)
        assertEquals(1, TrainingWeek.countIn(SUNDAY..SUNDAY))
    }

    @Test
    fun `an inverted period is a caller's bug, not a state to render`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrainingWeek.snap(SUNDAY..MONDAY)
        }
    }

    @Test
    fun `the week starts of a period are its Mondays, ascending`() {
        // A Wednesday to a Thursday nine days later: three weeks once widened.
        val starts = TrainingWeek.startsIn(MONDAY.plusDays(2)..MONDAY.plusDays(17))

        assertEquals(listOf(MONDAY, MONDAY.plusDays(7), MONDAY.plusDays(14)), starts)
        assertEquals(3, TrainingWeek.countIn(MONDAY.plusDays(2)..MONDAY.plusDays(17)))
    }

    @Test
    fun `the preceding period is the same number of weeks, ending the day before`() {
        val period = MONDAY..SUNDAY.plusDays(7) // two weeks

        val previous = TrainingWeek.preceding(period)

        assertEquals(MONDAY.minusDays(14), previous.start)
        assertEquals(MONDAY.minusDays(1), previous.endInclusive)
        assertEquals(TrainingWeek.countIn(period), TrainingWeek.countIn(previous))
    }

    @Test
    fun `the preceding period of a mid-week period is measured from the widened one`() {
        // Requested: a Wednesday to the next Wednesday - eight days, but two weeks widened,
        // so what comes before it is two weeks and not eight days.
        val previous = TrainingWeek.preceding(MONDAY.plusDays(2)..MONDAY.plusDays(9))

        assertEquals(MONDAY.minusDays(14), previous.start)
        assertEquals(MONDAY.minusDays(1), previous.endInclusive)
    }

    @Test
    fun `a trailing window ends with the week the given day falls in`() {
        // Asked on the Thursday of MONDAY's week: twelve weeks back, this one included.
        val window = TrainingWeek.trailing(weeks = 12, endingOn = MONDAY.plusDays(3))

        assertEquals(MONDAY.minusDays(11 * 7), window.start)
        assertEquals(SUNDAY, window.endInclusive)
        assertEquals(12, TrainingWeek.countIn(window))
    }

    @Test
    fun `a one-week trailing window is the current week alone`() {
        val window = TrainingWeek.trailing(weeks = 1, endingOn = MONDAY.plusDays(3))

        assertEquals(MONDAY, window.start)
        assertEquals(SUNDAY, window.endInclusive)
    }

    @Test
    fun `a trailing window of no weeks is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrainingWeek.trailing(weeks = 0, endingOn = MONDAY)
        }
    }
}
