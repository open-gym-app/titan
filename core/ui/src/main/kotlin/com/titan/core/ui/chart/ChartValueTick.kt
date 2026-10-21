package com.titan.core.ui.chart

import androidx.compose.runtime.Immutable

/**
 * One figure down the left of a plot, and the height it stands for.
 *
 * @property text the value as the user reads it, from the caller's own formatter — `92.5`,
 *   `8,400`. Without a unit: the card's badge carries that once, where three repetitions of
 *   `kg` down the side of an 88dp plot would carry it three times and cost the width to do it.
 * @property fraction where it sits in the band, `0f..1f` from the bottom. The same fraction
 *   the drawing scales against, so the tick and the mark it names are the same pixel — see
 *   [ChartGeometry].
 */
@Immutable
internal data class ChartValueTick(
    val text: String,
    val fraction: Float,
)
