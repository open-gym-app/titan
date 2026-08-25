package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ConsistencyTrend
import com.opengym.core.domain.model.WeeklyTraining
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.stats.TrainingWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/**
 * How often the user actually trains, week by week over a period, against the period before
 * it — the progression screen's `AVG / WEEK` figure and the `+0.4 vs prev` beside it.
 *
 * ### Days, not sessions
 *
 * A week's figure is the number of distinct **days** a session was finished on, not the
 * number of sessions: a lifter who benches in the morning and runs in the evening trained
 * once that day for consistency's purposes. Counting sessions instead would let a change in
 * how somebody splits their day read as a change in how often they show up, and would put a
 * ceiling of nothing on a figure the screen draws as "out of 7".
 * [WeeklyTraining.sessionCount] carries the other count for the surfaces that want it.
 *
 * The distinct count is done in SQL, where the sessions are; see [ProgressionRepository].
 *
 * ### Whole weeks, filled gaps, and the empty period
 *
 * The period is widened to whole weeks, untrained weeks are emitted as zero, and a period
 * with nothing in it comes back with [ConsistencyTrend.weeks] empty — the same three rules
 * [VolumeTrendUseCase] follows, and for the same reasons, with one that matters more here:
 * the average divides by every week of the period, so a fortnight off has to be present as
 * two zeroes. An average over only the weeks that had training in them would be a figure
 * that cannot fall, which is the opposite of a consistency metric.
 *
 * That is also why [ConsistencyTrend.averagePerWeek] cannot divide by zero: the week count
 * it divides by is the period's, which is at least one whenever there is anything to
 * average, and the empty period short-circuits to `0.0` without the division.
 *
 * [ConsistencyTrend.previousAveragePerWeek] is still filled in when the period before held
 * training, so an empty period is not [ConsistencyTrend.EMPTY] itself and must not be
 * compared against it — test [ConsistencyTrend.isFirstRun].
 *
 * ### Telling a break apart from a first run
 *
 * An empty period alone cannot: a long enough lapse empties the comparison period too, and a
 * lifter of three years would then read exactly as somebody who has never trained. So
 * [ProgressionRepository.hasTrainingHistory] is read beside the series and carried on
 * [ConsistencyTrend.hasEverTrained] — the same arrangement, and the same reasoning, as
 * [VolumeTrendUseCase], which spells out why this one extra flow is worth combining.
 */
class ConsistencyUseCase(
    private val progressionRepository: ProgressionRepository,
) {

    /**
     * The weekly training series for [period], plus the average of the equally long period
     * before it.
     *
     * @param period any range of days, both ends inclusive; widened to whole weeks before
     *   anything is read. [TrainingWeek.trailing] builds the usual one — "the last twelve
     *   weeks, this one included".
     */
    operator fun invoke(period: ClosedRange<LocalDate>): Flow<ConsistencyTrend> {
        val weeks = TrainingWeek.snap(period)
        val previous = TrainingWeek.preceding(weeks)
        return combine(
            progressionRepository.getWeeklyTraining(previous.start..weeks.endInclusive),
            progressionRepository.hasTrainingHistory(),
        ) { rows, hasEverTrained -> trend(weeks, previous, rows, hasEverTrained) }
    }

    private fun trend(
        weeks: ClosedRange<LocalDate>,
        previous: ClosedRange<LocalDate>,
        rows: List<WeeklyTraining>,
        hasEverTrained: Boolean,
    ): ConsistencyTrend {
        val (current, earlier) = rows.partition { it.weekStart >= weeks.start }
        // Averaged over the previous period's whole width, zero weeks included, exactly as
        // the current one is — comparing a filled average against a sparse one would report
        // a change that came from the arithmetic rather than from the training.
        val previousAverage = if (earlier.isEmpty()) {
            null
        } else {
            earlier.sumOf(WeeklyTraining::trainingDays).toDouble() / TrainingWeek.countIn(previous)
        }
        if (current.isEmpty()) {
            return ConsistencyTrend(
                weeks = emptyList(),
                previousAveragePerWeek = previousAverage,
                hasEverTrained = hasEverTrained,
            )
        }

        val byWeek = current.associateBy(WeeklyTraining::weekStart)
        return ConsistencyTrend(
            weeks = TrainingWeek.startsIn(weeks).map { start ->
                byWeek[start] ?: WeeklyTraining(weekStart = start, trainingDays = 0, sessionCount = 0)
            },
            previousAveragePerWeek = previousAverage,
            hasEverTrained = hasEverTrained,
        )
    }
}
