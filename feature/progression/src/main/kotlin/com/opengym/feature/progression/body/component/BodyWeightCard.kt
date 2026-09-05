package com.opengym.feature.progression.body.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.BodyMeasurement
import com.opengym.core.domain.model.milligramsPerUnit
import com.opengym.core.ui.chart.ChangeDirection
import com.opengym.core.ui.chart.ChartChange
import com.opengym.core.ui.chart.ChartEntry
import com.opengym.core.ui.chart.SeriesHeroCard
import com.opengym.core.ui.format.formatDayAndMonth
import com.opengym.core.ui.format.formatMonthAbbreviation
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.format.localDateOf
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.feature.progression.R
import kotlin.math.absoluteValue

/**
 * The hero: what the user weighs, and how that has moved across the window.
 *
 * ### The figure is now; the line and the chip are the window
 *
 * The number is the **latest weight ever recorded**, not the last one inside the selected
 * period, which is where this card parts company with the stats hub's. Cumulative volume is a
 * property of a window — narrow it and there is genuinely less of it — but a body weight is a
 * level: somebody who last weighed themselves five weeks ago and is looking at a fortnight
 * still weighs what the scale said, and blanking the figure would be the screen pretending not
 * to know. What the window governs is the *shape* under it and the change beside it.
 *
 * ### An arrow, and no opinion about which way it points
 *
 * A rise takes the accent because `ChangeChip` spends it on rises, and that is all it means
 * here: this app does not know whether the user is cutting or bulking, and painting a loss as
 * good or a gain as bad would be it guessing. The arrow says which way; the magnitude is drawn
 * unsigned beside it.
 *
 * @param weights the entries in the window that recorded a weight, oldest first.
 * @param latest the most recent weight recorded, in milligrams, or null when there is none.
 * @param change how far the weight moved across the window, in milligrams and **signed**, or
 *   null with fewer than two readings in it.
 */
@Composable
fun BodyWeightCard(
    weights: List<BodyMeasurement>,
    latest: Long?,
    change: Long?,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.body_weight_label)
    val unitSystem = LocalUnitSystem.current

    SeriesHeroCard(
        label = label,
        // A dash rather than a zero for a user who has measured everything but their weight:
        // `0 kg` is a reading somebody took, and this one has not been.
        value = latest?.let { formatWeightValue(it, unitSystem) } ?: stringResource(R.string.body_no_reading),
        unit = latest?.let { unitSystem.weightSuffix },
        entries = weights.map { it.chartEntry() },
        change = change?.let { delta ->
            ChartChange(
                // The smallest move the chip can draw: half of the last decimal `formatWeight`
                // renders, in the unit it renders in. Below it the magnitude reads as `0 kg`,
                // and an arrow beside a zero is the drawing and the description disagreeing —
                // the same tie `VolumeHeroCard` makes between its tolerance and its formatter.
                direction = ChangeDirection.of(delta.toDouble(), WEIGHT_ROUNDING * unitSystem.milligramsPerUnit),
                value = formatWeight(delta.absoluteValue, unitSystem),
            )
        },
        modifier = modifier,
    )
}

/**
 * One weigh-in as a mark: the weight, the month it fell in, and the day a tooltip names it by.
 *
 * Two dates for the reason the exercise charts carry two — the axis collapses a run of `Apr`
 * into one label, and a tooltip on the fourth of five April weigh-ins has to say which.
 */
@Composable
private fun BodyMeasurement.chartEntry(): ChartEntry {
    // Non-null by construction: the caller filters the series to the entries that recorded a
    // weight, because an entry that measured a waist alone is not a day the user weighed
    // nothing — see `BodyMeasurementsUiState.weights`.
    val milligrams = requireNotNull(weight) { "a weight series holds only entries that recorded one" }

    return ChartEntry(
        // Milligrams run to eleven digits for a body weight and a `Float` holds seven of them,
        // which is a relative error far finer than a pixel on this chart. The exact figure
        // travels as a string in `formattedValue`, where it is read rather than plotted.
        value = milligrams.toFloat(),
        label = formatMonthAbbreviation(localDateOf(createdAt)),
        formattedValue = formatWeight(milligrams),
        caption = formatDayAndMonth(createdAt),
    )
}

/**
 * Half of the last decimal a weight is drawn to.
 *
 * `formatWeightValue` renders two, so anything at or above this shows as `0.01` or more and
 * anything below it rounds away to nothing. Expressed in display units and multiplied up by
 * the caller, because a hundredth of a kilogram and a hundredth of a pound are not the same
 * number of milligrams.
 */
private const val WEIGHT_ROUNDING = 0.005
