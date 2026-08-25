package com.opengym.core.domain.model

/**
 * What [com.opengym.core.domain.usecase.VolumeTrendUseCase] answers with: a period's volume
 * week by week, and the same period's worth of volume immediately before it.
 *
 * @property weeks one bucket per week of the requested period, ascending and contiguous —
 *   or **empty**, and only when nothing was lifted in the period at all. Both a first-time
 *   user and a lapsed one read as empty here; [hasEverTrained] is what tells them apart,
 *   and [isFirstRun] / [isQuietPeriod] name the two states so a screen does not have to
 *   pair the conditions itself.
 * @property previousTotalVolume the preceding period's total, or **null** when that period
 *   holds no history — a first-time user has nothing to be compared against, and inventing
 *   a zero for them would report an infinite improvement.
 * @property hasEverTrained whether the user has finished a session at *any* point, not only
 *   within the two periods read. Unbounded on purpose: a lapse longer than the period plus
 *   its comparison empties both, so nothing inside the window can distinguish a returning
 *   lifter from a new one.
 */
data class VolumeTrend(
    val weeks: List<WeeklyVolume>,
    val previousTotalVolume: Long?,
    val hasEverTrained: Boolean,
) {

    /** The period's volume, in milligram-reps. The sum of [weeks], since they cover it whole. */
    val totalVolume: Long get() = weeks.sumOf(WeeklyVolume::totalVolume)

    /**
     * Nothing in the period and nothing before it either: the user has never finished a
     * session. The condition for the "log your first session" prompt, and the only state in
     * which a screen should draw one — an empty chart is not on its own evidence of a new
     * user.
     */
    val isFirstRun: Boolean get() = weeks.isEmpty() && !hasEverTrained

    /**
     * Nothing in the period, but the user has trained before — a break, not a first run.
     * Worth saying so rather than prompting somebody with three years of history to log
     * their first session. [previousTotalVolume] fills in what they were doing before only
     * when the lapse is short enough to reach into the comparison period.
     */
    val isQuietPeriod: Boolean get() = weeks.isEmpty() && hasEverTrained

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
        /** A user with no training history at all — what [isFirstRun] is true of. */
        val EMPTY = VolumeTrend(
            weeks = emptyList(),
            previousTotalVolume = null,
            hasEverTrained = false,
        )
    }
}
