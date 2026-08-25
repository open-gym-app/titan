package com.opengym.core.domain.model

/**
 * What [com.opengym.core.domain.usecase.VolumeTrendUseCase] answers with: a period's volume
 * week by week, and the same period's worth of volume immediately before it.
 *
 * @property weeks one bucket per week of the requested period, ascending and contiguous —
 *   or **empty**, and only when nothing was lifted in the period at all. A user with no
 *   history reads as empty, which is what lets a screen draw a "log your first session"
 *   prompt rather than a chart of zeroes; see the use case for why a lapsed user's window
 *   reads the same way.
 * @property previousTotalVolume the preceding period's total, or **null** when that period
 *   holds no history — a first-time user has nothing to be compared against, and inventing
 *   a zero for them would report an infinite improvement.
 */
data class VolumeTrend(
    val weeks: List<WeeklyVolume>,
    val previousTotalVolume: Long?,
) {

    /** The period's volume, in milligram-reps. The sum of [weeks], since they cover it whole. */
    val totalVolume: Long get() = weeks.sumOf(WeeklyVolume::totalVolume)

    /**
     * [totalVolume] against [previousTotalVolume] as a signed fraction — `0.12` for a 12%
     * increase — or **null** when there is nothing to compare against.
     *
     * Null covers both ways that can happen: no previous history at all, and a previous
     * period that held sessions but lifted nothing (all cardio, all bodyweight). The second
     * is the division by zero, and it is genuinely unanswerable rather than infinite: going
     * from no volume to some is not a percentage of anything.
     */
    val volumeChangeRatio: Double? get() {
        val previous = previousTotalVolume?.takeIf { it != 0L } ?: return null
        return (totalVolume - previous).toDouble() / previous
    }

    companion object {
        /** Nothing lifted in either period. */
        val EMPTY = VolumeTrend(weeks = emptyList(), previousTotalVolume = null)
    }
}
