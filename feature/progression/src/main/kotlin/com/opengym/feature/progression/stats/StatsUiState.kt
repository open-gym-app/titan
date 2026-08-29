package com.opengym.feature.progression.stats

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.MuscleTraining
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.model.WeeklyVolume

/**
 * Everything the stats hub draws, as one value.
 *
 * ### Canonical figures, not formatted ones
 *
 * Every number here is in the unit it is stored in — milligram-reps, milliseconds, counts —
 * and none of it is a string. Formatting reads `LocalUnitSystem`, which is ambient in the
 * composition, so a ViewModel that formatted would have to be told whether the user is on
 * kilograms and would then hold state that goes stale the moment they change it. The screen
 * turns these into the text and the [com.opengym.core.ui.chart.ChartEntry]s it draws; see
 * CONTRIBUTING.md § Using the design system.
 *
 * ### One nullable block per section
 *
 * The same shape Home takes: a section with nothing to say is **null** and is not drawn,
 * rather than drawn empty. A chart of zeros and a stat grid of noughts are the thing #32 asks
 * this screen not to show a new user, and a flag per section would let the two disagree.
 *
 * @param timeframe the window every figure below is read over. Shared with #102 — see
 *   [ProgressionTimeframe].
 * @param isLoading true until every read has answered once. One flag rather than one per
 *   query, because the reads are combined and the first combined value arrives only when all
 *   of them have.
 * @param emptiness why there is nothing to draw, or **null** when there is. It separates the
 *   two ways a period comes back empty — see [StatsEmptiness] — which no amount of looking at
 *   the figures can.
 * @param volume the hero: the period's total, the weekly series under it, and the change
 *   against the period before. Null when the period holds no training.
 * @param summary the four-up grid. Null with [volume], since both are readings of the same
 *   empty period.
 * @param muscles what was trained, ranked by sets. Empty for a period with no completed sets
 *   in it, which includes a period of nothing but cardio: the map is built from the muscle
 *   links of the exercises performed, and those exist for cardio too, so an empty list here
 *   really does mean nothing was logged.
 */
@Immutable
data class StatsUiState(
    val timeframe: StatsTimeframe = StatsTimeframe.DEFAULT,
    val isLoading: Boolean = true,
    val emptiness: StatsEmptiness? = null,
    val volume: VolumeState? = null,
    val summary: SummaryState? = null,
    val muscles: List<MuscleTraining> = emptyList(),
)

/**
 * Why the period has nothing in it.
 *
 * Two states, because they want opposite things said to them and nothing in the figures tells
 * them apart — a lifter of three years who took six months off empties a ninety-day window
 * exactly as thoroughly as somebody who installed the app this morning. See
 * [com.opengym.core.domain.model.VolumeTrend.hasEverTrained], which is the bit that survives
 * any length of gap.
 */
enum class StatsEmptiness {

    /** Nothing, ever. The one state that gets a "log your first session" prompt. */
    FIRST_RUN,

    /**
     * Nothing in *this* window, but there is history behind it.
     *
     * Prompting somebody with three years of training to log their first session is the
     * failure this state exists to avoid. The screen says the window is quiet and offers a
     * wider one instead.
     */
    QUIET_PERIOD,
}

/**
 * The hero card: cumulative volume over the period, and where it is going.
 *
 * @param total the period's volume in milligram-reps. The exact sum of [weeks], because the
 *   period was widened to whole weeks before anything was read.
 * @param weeks one bucket per week of the period, ascending and contiguous, untrained weeks
 *   included as zeroes — a rest week drawn as a gap would join the weeks either side of it
 *   with a line claiming training continued.
 * @param changeRatio the period against the one before it as a signed fraction — `0.18` for
 *   18% more — or **null** when there is nothing to compare against. Null covers a user with
 *   no earlier history *and* an earlier period that trained but lifted nothing, which is not
 *   an infinite improvement but an unanswerable question.
 */
@Immutable
data class VolumeState(
    val total: Long,
    val weeks: List<WeeklyVolume>,
    val changeRatio: Double?,
)

/**
 * The four-up grid: sessions, average per week, records broken, time spent.
 *
 * @param sessions finished sessions in the period.
 * @param averagePerWeek days trained per week, averaged over **every** week of the period
 *   including the empty ones — a fortnight off is what a consistency figure is for.
 * @param averageChange the same figure against the previous period, in days per week, or null
 *   when there is no previous period. A difference rather than a ratio: the figure runs 0 to 7
 *   and is read directly, so "half a day a week more" says more than "13% more".
 * @param records how many personal records were broken, and across how many exercises.
 * @param trainingMillis how long the period's sessions ran for in total.
 */
@Immutable
data class SummaryState(
    val sessions: Int,
    val averagePerWeek: Double,
    val averageChange: Double?,
    val records: RecordTally,
    val trainingMillis: Long,
)
