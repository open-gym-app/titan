package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.VolumeTrend
import com.opengym.core.domain.model.WeeklyVolume
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.stats.TrainingWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Training volume over a period, one figure per week, with the period before it to compare
 * against — the series behind the progression screen's hero chart and its change chip.
 *
 * ### Nothing is summed here
 *
 * The arithmetic runs in SQLite; this reads back one row per week that had training in it
 * (see [ProgressionRepository]) and turns that into the series a chart can draw. A three-
 * year history is a few hundred rows either way, and no set of it is ever loaded — which is
 * the difference between this and [com.opengym.core.domain.stats.VolumeCalculator], the
 * in-memory twin for a session the caller is already holding.
 *
 * ### The period is widened to whole weeks
 *
 * "Last 30 days" ends on whatever day it is today, and a final bucket holding three days of
 * training beside buckets holding seven is a fall in the chart that nothing in the gym
 * caused. So the requested period is [TrainingWeek.snap]ped outward and the answer covers
 * whole weeks, which also makes [VolumeTrend.totalVolume] the exact sum of what it charts.
 *
 * ### Gaps are zeroes, but an empty period is empty
 *
 * A week nobody trained in produces no row, and drawing the series straight from the rows
 * would join the week either side of a rest week with a line that says training continued.
 * So every week of the period is emitted, the untrained ones as zero.
 *
 * The exception is a period with **no** rows at all, which comes back as [VolumeTrend.EMPTY]
 * rather than as a run of zeroes. A brand-new user has to reach a "log your first session"
 * prompt and not a flat chart of nothing, and the empty list is what a screen keys that off.
 * The same reading applies to a lapsed user's quiet window, which is the honest one: the
 * period holds nothing, and [VolumeTrend.previousTotalVolume] still says what came before it.
 *
 * ### One read, two periods
 *
 * The comparison period is fetched by widening the query rather than by making a second one:
 * both periods are contiguous whole weeks, so `[previous.start, period.end]` is a single
 * range that splits on the boundary once the rows come back. Two flows would also have to be
 * combined, and a combine of two database reads emits a torn pair on the way to a settled one
 * every time a set is logged.
 */
class VolumeTrendUseCase(
    private val progressionRepository: ProgressionRepository,
) {

    /**
     * The weekly volume series for [period], plus the total of the equally long period
     * before it.
     *
     * @param period any range of days, both ends inclusive; it is widened to whole weeks
     *   before anything is read. Must not be empty (`start` after `endInclusive`), which
     *   is a caller's bug rather than a state to render.
     */
    operator fun invoke(period: ClosedRange<LocalDate>): Flow<VolumeTrend> {
        val weeks = TrainingWeek.snap(period)
        val previous = TrainingWeek.preceding(weeks)
        return progressionRepository.getWeeklyVolume(previous.start..weeks.endInclusive)
            .map { rows -> trend(weeks, rows) }
    }

    private fun trend(weeks: ClosedRange<LocalDate>, rows: List<WeeklyVolume>): VolumeTrend {
        val (current, earlier) = rows.partition { it.weekStart >= weeks.start }
        // Null, not 0: see `VolumeTrend.previousTotalVolume` for why an absent period and a
        // period that lifted nothing are different answers.
        val previousTotal = if (earlier.isEmpty()) null else earlier.sumOf(WeeklyVolume::totalVolume)
        if (current.isEmpty()) return VolumeTrend(weeks = emptyList(), previousTotalVolume = previousTotal)

        val byWeek = current.associateBy(WeeklyVolume::weekStart)
        return VolumeTrend(
            weeks = TrainingWeek.startsIn(weeks).map { start ->
                byWeek[start] ?: WeeklyVolume(weekStart = start, totalVolume = 0L)
            },
            previousTotalVolume = previousTotal,
        )
    }
}
