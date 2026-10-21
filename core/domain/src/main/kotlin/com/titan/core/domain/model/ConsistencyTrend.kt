package com.titan.core.domain.model

/**
 * What [com.titan.core.domain.usecase.ConsistencyUseCase] answers with: how often the user
 * trained, week by week, and how that compares with the period before.
 *
 * The shape the progression screen's `AVG / WEEK - 3.2 - +0.4 vs prev` is drawn from, which
 * is why the comparison is part of the answer rather than something a caller assembles from
 * two calls it would have to keep in step.
 *
 * @property weeks one bucket per week of the requested period, ascending and contiguous —
 *   or **empty** when nothing was trained in the period at all, matching [VolumeTrend.weeks].
 * @property previousAveragePerWeek the preceding period's [averagePerWeek], or **null** when
 *   that period holds no history at all. Null rather than `0.0` because a user who had not
 *   started yet did not train zero days a week; they had no weeks.
 * @property hasEverTrained whether the user has finished a session at any point, in or out
 *   of the periods read — see [VolumeTrend.hasEverTrained], which carries the same signal
 *   for the same reason.
 */
data class ConsistencyTrend(
    val weeks: List<WeeklyTraining>,
    val previousAveragePerWeek: Double?,
    val hasEverTrained: Boolean,
) {

    /** Days trained across the period. */
    val totalTrainingDays: Int get() = weeks.sumOf(WeeklyTraining::trainingDays)

    /** No training in the period and none before it: see [VolumeTrend.isFirstRun]. */
    val isFirstRun: Boolean get() = weeks.isEmpty() && !hasEverTrained

    /** A break rather than a first run: see [VolumeTrend.isQuietPeriod]. */
    val isQuietPeriod: Boolean get() = weeks.isEmpty() && hasEverTrained

    /** Sessions finished across the period — at least [totalTrainingDays]. */
    val totalSessions: Int get() = weeks.sumOf(WeeklyTraining::sessionCount)

    /**
     * Days trained per week over the period, averaged over **every** week in it including
     * the ones with nothing in them — a fortnight off is what a consistency figure is for.
     *
     * `0.0` for an empty period rather than a division by zero: [weeks] is empty exactly
     * when nothing was trained, and "no training" averages to none.
     */
    val averagePerWeek: Double
        get() = if (weeks.isEmpty()) 0.0 else totalTrainingDays.toDouble() / weeks.size

    /**
     * [averagePerWeek] against [previousAveragePerWeek], in days per week — `+0.4` for four
     * tenths of a *day* more each week — or **null** when there is no previous period to
     * compare against. Days, not sessions: see [WeeklyTraining] for why the two differ.
     *
     * A difference rather than a ratio, unlike [VolumeTrend.volumeChangeRatio]: this figure
     * runs from 0 to 7 and is read directly, so "half a day a week more" says more than
     * "13% more" and has no zero to divide by.
     */
    val changePerWeek: Double? get() = previousAveragePerWeek?.let { averagePerWeek - it }

    companion object {
        /** A user with no training history at all — what [isFirstRun] is true of. */
        val EMPTY = ConsistencyTrend(
            weeks = emptyList(),
            previousAveragePerWeek = null,
            hasEverTrained = false,
        )
    }
}
