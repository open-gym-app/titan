package com.opengym.core.domain.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The week the progression aggregates are bucketed by: **Monday to Sunday, UTC**.
 *
 * One definition, in one place, because two of them would not stay equal. The buckets are
 * cut in SQL — `WorkoutSetDao.getVolumeByWeek` and `WorkoutSessionDao.getTrainingByWeek`
 * both round an epoch day down with `day - ((day + 3) % 7)`, epoch day 0 having been a
 * Thursday — and the use cases above them have to be able to say which weeks a period
 * *should* contain before they have seen a single row, so that a week nobody trained in
 * reads as a zero rather than as a gap. That arithmetic and this object are the same rule
 * stated twice, and `TrainingWeekParityTest` in `:core:data` is what holds them to it.
 *
 * **Monday** rather than the formatting locale's first day, unlike
 * `core/ui`'s `TrainingCalendar`, which draws a *calendar* and should look like the user's.
 * A bucket is not a drawing: shifting the boundary would move volume between buckets, so a
 * user who changed their locale would see last month's history change shape. ISO-8601 says
 * Monday, so Monday it is.
 *
 * **UTC** for the same reason the rows are stamped in it — `completed_at` is epoch millis —
 * and it is a genuine approximation: a lifter in UTC-5 who finishes at 20:00 on a Sunday is
 * bucketed into the following week. Correcting that means a time zone in every query, and
 * the zone to use would be the one the set was *logged* in rather than the one the phone is
 * in now. It is written down here rather than fixed.
 */
object TrainingWeek {

    /** ISO-8601's first day, and the day every bucket in this codebase opens on. */
    val FIRST_DAY: DayOfWeek = DayOfWeek.MONDAY

    /** Days to a week. Named so the `plusDays` calls below read as what they are. */
    const val DAYS: Long = 7

    /** The Monday that opens [date]'s week — [date] itself when it is already a Monday. */
    fun startOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(FIRST_DAY))

    /**
     * [period] widened to the whole weeks it touches: back to the Monday that opens its
     * first week, forward to the Sunday that closes its last.
     *
     * Every period this file produces is snapped, and that is what makes the series
     * comparable to itself. A "last 30 days" window ends mid-week, and a bucket holding
     * three days of training next to buckets holding seven is a dip in the chart that
     * nothing in the gym caused. Widening also keeps a period's total equal to the sum of
     * its buckets, which clipping the end bucket to the requested day would not.
     */
    fun snap(period: ClosedRange<LocalDate>): ClosedRange<LocalDate> {
        require(!period.isEmpty()) { "a training period must not be empty: $period" }
        return startOf(period.start)..startOf(period.endInclusive).plusDays(DAYS - 1)
    }

    /**
     * The Monday of every week in [period], ascending, [period] having been [snap]ped first.
     *
     * The x-axis of a trend, worked out from the period rather than from the rows, so a week
     * with nothing in it can be drawn as the zero it is.
     */
    fun startsIn(period: ClosedRange<LocalDate>): List<LocalDate> {
        val weeks = snap(period)
        return generateSequence(weeks.start) { it.plusDays(DAYS) }
            .takeWhile { it <= weeks.endInclusive }
            .toList()
    }

    /** How many whole weeks [period] spans once [snap]ped. At least one, for any period. */
    fun countIn(period: ClosedRange<LocalDate>): Int {
        val weeks = snap(period)
        return (ChronoUnit.DAYS.between(weeks.start, weeks.endInclusive) / DAYS).toInt() + 1
    }

    /**
     * The period of the same length ending the day before [period] opens — what "vs prev"
     * on the progression screen compares against.
     *
     * Both ends come from the [snap]ped [period], so the two are the same whole number of
     * weeks and neither can borrow a day from the other.
     */
    fun preceding(period: ClosedRange<LocalDate>): ClosedRange<LocalDate> {
        val weeks = snap(period)
        val length = countIn(weeks) * DAYS
        return weeks.start.minusDays(length)..weeks.start.minusDays(1)
    }

    /**
     * The trailing window of [weeks] whole weeks ending with the one [endingOn] falls in —
     * the shape the consistency metric is read over ("days trained per week, last 12 weeks").
     *
     * The current, incomplete week is *included* rather than dropped: it is the one the user
     * is living in, and a consistency figure that ignored it would not move until Monday.
     */
    fun trailing(
        weeks: Int,
        endingOn: LocalDate,
    ): ClosedRange<LocalDate> {
        require(weeks > 0) { "a trailing window must span at least one week: $weeks" }
        val last = startOf(endingOn)
        return last.minusDays((weeks - 1) * DAYS)..last.plusDays(DAYS - 1)
    }
}
