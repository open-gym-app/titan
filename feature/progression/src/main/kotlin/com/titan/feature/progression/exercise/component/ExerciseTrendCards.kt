package com.titan.feature.progression.exercise.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.model.ExerciseAttempt
import com.titan.core.domain.model.ExerciseEstimate
import com.titan.core.domain.model.ExerciseProgression
import com.titan.core.ui.chart.ChartCard
import com.titan.core.ui.chart.ChartDefaults
import com.titan.core.ui.chart.ChartEntry
import com.titan.core.ui.chart.ChartScale
import com.titan.core.ui.component.TitanBadge
import com.titan.core.ui.format.distanceUnitFor
import com.titan.core.ui.format.formatClockValue
import com.titan.core.ui.format.formatDayAndMonth
import com.titan.core.ui.format.formatDistance
import com.titan.core.ui.format.formatDistanceValue
import com.titan.core.ui.format.formatEstimate
import com.titan.core.ui.format.formatEstimateValue
import com.titan.core.ui.format.formatMonthAbbreviation
import com.titan.core.ui.format.formatVolume
import com.titan.core.ui.format.formatVolumeValue
import com.titan.core.ui.format.localDateOf
import com.titan.core.ui.format.weightSuffix
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R

/**
 * The two charts: where the lift is going, and what each session cost.
 *
 * ### Two charts because they answer two questions
 *
 * The estimate is a **line**, scaled to its own floor and ceiling, because what is being read
 * is the direction — a lifter going 90 → 92 kg should see a rise rather than two marks of the
 * same height. The volume is **bars**, measured against zero, because those are quantities
 * standing side by side and the question is which session was the big one. `:core:ui` owns
 * both decisions; see `LineSeriesChart` and `BarSeriesChart`.
 *
 * ### The two series are not the same length, on purpose
 *
 * Every attempt has a volume — a session of timed holds moved nothing, and zero is the truth
 * about it — while only an attempt with a weight *and* a rep count projects a one-rep max. So
 * the line can be shorter than the bars, and a lift done for time on some weeks draws a line
 * across the sessions it can be estimated from rather than a line that collapses to the floor
 * on the ones it cannot. See [ExerciseProgression.estimates].
 *
 * ### A timed exercise is charted by what it did
 *
 * A run or a hold has no one-rep max and no volume, so both cards would be empty or a row of
 * zeroes (#M7-17). For such an exercise — see [AttemptMeasure] — the projection card goes, and
 * the bars are the ground each session covered, or how long its holds lasted.
 *
 * ### The formatting happens here
 *
 * The domain carries milligrams and epoch millis; this turns them into what the user reads,
 * through the formatters that follow the ambient unit system — so switching to pounds repaints
 * both charts without the ViewModel being told. That is the split CONTRIBUTING.md § Using the
 * design system asks for, and it is why [ChartEntry] carries a formatted string beside its
 * value.
 *
 * The value axis is the same rule one step further out: `:core:ui` works out *which* figures a
 * band needs and this says what they read as, through the explicit-unit overloads over a unit
 * system read once here. The rail's figures carry no unit — the badge on the heading says it
 * once, which is why the volume card grew one when the rail arrived.
 *
 * ### Two dates per mark, because the axis and the tooltip are asking different things
 *
 * [ChartEntry.label] is the month, which is what makes `FEB · MAR · APR` out of twelve marks;
 * [ChartEntry.caption] is the day, because a tooltip on the fourth of five April sessions has
 * to say *which* — see `chartAxisLabels`.
 */
@Composable
fun ExerciseTrendCards(
    progression: ExerciseProgression,
    modifier: Modifier = Modifier,
) {
    val estimateLabel = stringResource(R.string.exercise_chart_estimate)
    val unitSystem = LocalUnitSystem.current
    val unitSuffix = unitSystem.weightSuffix
    val measure = AttemptMeasure.of(progression.attempts)

    if (measure != AttemptMeasure.VOLUME) {
        TimedTrendCard(progression = progression, measure = measure, modifier = modifier)
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        ChartCard(
            label = estimateLabel,
            entries = progression.estimates.map { it.chartEntry() },
            scale = ChartScale.LINE,
            // The taller of the two heights: this is the card the screen is about, where the
            // bars under it are a second reading of the same sessions.
            height = ChartDefaults.TALL_HEIGHT,
            // The estimate formatter, not the weight one: the rail reads a projection, and its
            // two decimals would put `1,333.33` on three gridlines for a figure the formula is
            // not accurate to. See `formatEstimateValue`.
            formatValue = { milligrams -> formatEstimateValue(milligrams.toLong(), unitSystem) },
            // The unit the line is in, as the wireframes tag it. It answers the rail's figures
            // as well as the plot: an estimate is a weight the lifter has never actually
            // touched, so what it is measured in is the first question it raises.
            trailing = { TitanBadge(text = unitSuffix) },
        )

        ChartCard(
            label = stringResource(R.string.exercise_chart_volume),
            entries = progression.attempts.map { it.chartEntry() },
            scale = ChartScale.BAR,
            formatValue = { milligrams -> formatVolumeValue(milligrams.toLong(), unitSystem) },
            trailing = { TitanBadge(text = unitSuffix) },
        )
    }
}

/**
 * The one chart a timed exercise draws: distance or time per session, as bars against zero.
 *
 * The distance is read in one unit for the whole chart — the one the longest session reads best
 * in — because a rail of `400 m` gridlines under a bar labelled `1.2 km` is two scales on one
 * axis. The time needs no unit badge: `27:14` says what it is.
 */
@Composable
private fun TimedTrendCard(
    progression: ExerciseProgression,
    measure: AttemptMeasure,
    modifier: Modifier = Modifier,
) {
    val unitSystem = LocalUnitSystem.current
    val distanceUnit = unitSystem.distanceUnitFor(progression.attempts.maxOfOrNull { it.distance } ?: 0)
    val isDistance = measure == AttemptMeasure.DISTANCE

    ChartCard(
        label = stringResource(if (isDistance) R.string.exercise_chart_distance else R.string.exercise_chart_time),
        entries = progression.attempts.map { attempt ->
            val value = if (isDistance) attempt.distance else attempt.timedSeconds
            ChartEntry(
                value = value.toFloat(),
                label = formatMonthAbbreviation(localDateOf(attempt.finishedAt)),
                formattedValue = if (isDistance) formatDistance(value, distanceUnit) else formatClockValue(value),
                caption = formatDayAndMonth(attempt.finishedAt),
            )
        },
        scale = ChartScale.BAR,
        height = ChartDefaults.TALL_HEIGHT,
        formatValue = { value ->
            if (isDistance) formatDistanceValue(value.toLong(), distanceUnit) else formatClockValue(value.toLong())
        },
        trailing = if (isDistance) {
            { TitanBadge(text = distanceUnit.suffix) }
        } else {
            null
        },
        modifier = modifier,
    )
}

/**
 * One projection as a mark: the estimate to plot, the month it fell in, and the weight a
 * screen reader is given.
 */
@Composable
private fun ExerciseEstimate.chartEntry(): ChartEntry =
    ChartEntry(
        // Milligrams run to nine digits for a weight anybody lifts and a `Float` holds seven of
        // them, which is a relative error far finer than a pixel on this chart. The exact
        // figure travels as a string in `formattedValue`, where it is read rather than plotted.
        value = estimate.toFloat(),
        label = formatMonthAbbreviation(localDateOf(finishedAt)),
        formattedValue = formatEstimate(estimate),
        caption = formatDayAndMonth(finishedAt),
    )

/** One attempt as a bar: what the session moved, and when. */
@Composable
private fun ExerciseAttempt.chartEntry(): ChartEntry =
    ChartEntry(
        value = volume.toFloat(),
        label = formatMonthAbbreviation(localDateOf(finishedAt)),
        formattedValue = formatVolume(volume),
        caption = formatDayAndMonth(finishedAt),
    )
