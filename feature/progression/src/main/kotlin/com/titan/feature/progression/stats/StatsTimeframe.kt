package com.titan.feature.progression.stats

import androidx.annotation.StringRes
import com.titan.core.domain.stats.TrainingWeek
import com.titan.feature.progression.R
import java.time.LocalDate

/**
 * The window every figure on the progression screens is read over: `7D`, `30D`, `90D`, `1Y`,
 * `ALL`, as the wireframes draw the selector.
 *
 * ### Days, then whole weeks
 *
 * Each entry names a number of **days**, which is what the label promises, and the aggregates
 * then widen that to whole weeks — see [TrainingWeek.snap]. So `30D` is "the last thirty days,
 * rounded out to the Mondays either side of them", and its total is the exact sum of the weeks
 * it charts rather than a figure with a part-week hanging off the end.
 *
 * The consequence worth knowing is at the short end: `7D` widens to two weekly buckets on every
 * day but Sunday, when it is exactly one — and a one-bucket series is not a trend, so the hero
 * chart says so rather than drawing a single point. The figure and its comparison chip are
 * unaffected; they are sums over the window, not a series across it.
 *
 * ### All time starts where the training does
 *
 * [ALL] has no length; its period opens on the day the user first finished a session. A fixed
 * date far enough back would be a window that widens every scan for the life of the install,
 * and "the last ten years" is a number somebody picked. A user with no history at all gets a
 * period of today alone, which reads as empty — which they are.
 */
enum class StatsTimeframe(
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    private val days: Long?,
) {
    WEEK(R.string.timeframe_week, R.string.timeframe_week_description, days = 7),
    MONTH(R.string.timeframe_month, R.string.timeframe_month_description, days = 30),
    QUARTER(R.string.timeframe_quarter, R.string.timeframe_quarter_description, days = 90),
    YEAR(R.string.timeframe_year, R.string.timeframe_year_description, days = 365),
    ALL(R.string.timeframe_all, R.string.timeframe_all_description, days = null),
    ;

    /**
     * The days this timeframe covers, ending [today] and both ends inclusive.
     *
     * @param firstTrainingDay when the user's history starts, for [ALL]. Ignored by every other
     *   entry — a `90D` window is ninety days whether or not the user has ninety days of
     *   training, because the point of the comparison chip is that an empty period is visible
     *   against a full one.
     */
    fun period(
        today: LocalDate,
        firstTrainingDay: LocalDate? = null,
    ): ClosedRange<LocalDate> {
        val start = when {
            days != null -> today.minusDays(days - 1)

            // Not before today: a period whose start is after its end is rejected by
            // `TrainingWeek.snap`, and a user with no history is exactly that caller.
            else -> firstTrainingDay?.coerceAtMost(today) ?: today
        }
        return start..today
    }

    companion object {
        /**
         * What the screen opens on, and what the wireframes draw as selected.
         *
         * Ninety days is the shortest window a *trend* is legible over: it is a dozen weekly
         * buckets, enough for a plateau to look like a plateau rather than like a bad week, and
         * short enough that a lifter recognises the training in it. The two shorter windows are
         * for answering "how has this month gone"; the two longer ones are for a year in review.
         */
        val DEFAULT = QUARTER
    }
}
