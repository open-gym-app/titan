package com.opengym.feature.progression.exercise.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.ExerciseAttempt
import com.opengym.core.domain.model.ExerciseEstimate
import com.opengym.core.domain.model.ExerciseProgression
import com.opengym.core.ui.chart.BarSeriesChart
import com.opengym.core.ui.chart.ChartCard
import com.opengym.core.ui.chart.ChartDefaults
import com.opengym.core.ui.chart.ChartEntry
import com.opengym.core.ui.chart.LineSeriesChart
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.format.formatMonthAbbreviation
import com.opengym.core.ui.format.formatVolume
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.format.localDateOf
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R

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
 * ### The formatting happens here
 *
 * The domain carries milligrams and epoch millis; this turns them into what the user reads,
 * through the formatters that follow the ambient unit system — so switching to pounds repaints
 * both charts without the ViewModel being told. That is the split CONTRIBUTING.md § Using the
 * design system asks for, and it is why [ChartEntry] carries a formatted string beside its
 * value.
 */
@Composable
fun ExerciseTrendCards(
    progression: ExerciseProgression,
    modifier: Modifier = Modifier,
) {
    val estimateLabel = stringResource(R.string.exercise_chart_estimate)
    val volumeLabel = stringResource(R.string.exercise_chart_volume)
    val unitSuffix = LocalUnitSystem.current.weightSuffix

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        ChartCard(
            label = estimateLabel,
            entries = progression.estimates.map { it.chartEntry() },
            // The unit the line is in, as the wireframes tag it. Worth saying on this card and
            // not on the bars below: an estimate is a weight the lifter has never actually
            // touched, so what it is measured in is the first question it raises.
            trailing = { OpenGymBadge(text = unitSuffix) },
        ) { entries, label ->
            // The taller of the two heights: this is the card the screen is about, where the
            // bars under it are a second reading of the same sessions.
            LineSeriesChart(entries = entries, label = label, height = ChartDefaults.TALL_HEIGHT)
        }

        ChartCard(
            label = volumeLabel,
            entries = progression.attempts.map { it.chartEntry() },
        ) { entries, label ->
            BarSeriesChart(entries = entries, label = label)
        }
    }
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
        formattedValue = formatWeight(estimate),
    )

/** One attempt as a bar: what the session moved, and when. */
@Composable
private fun ExerciseAttempt.chartEntry(): ChartEntry =
    ChartEntry(
        value = volume.toFloat(),
        label = formatMonthAbbreviation(localDateOf(finishedAt)),
        formattedValue = formatVolume(volume),
    )
